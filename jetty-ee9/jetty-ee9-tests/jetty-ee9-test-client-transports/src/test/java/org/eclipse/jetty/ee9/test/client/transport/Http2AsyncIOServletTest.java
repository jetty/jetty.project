//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Http2AsyncIOServletTest
{
    private final HttpConfiguration httpConfig = new HttpConfiguration();
    private Server server;
    private ServerConnector connector;
    private HTTP2Client client;

    private void start(HttpServlet httpServlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory(httpConfig));
        server.addConnector(connector);
        ServletContextHandler servletContextHandler = new ServletContextHandler(server, "/");
        servletContextHandler.addServlet(new ServletHolder(httpServlet), "/*");
        server.start();

        client = new HTTP2Client();
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testStartAsyncThenClientResetRemoteErrorNotification(boolean notify) throws Exception
    {
        httpConfig.setNotifyRemoteAsyncErrors(notify);
        AtomicReference<AsyncEvent> errorAsyncEventRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onComplete(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onTimeout(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event)
                    {
                        errorAsyncEventRef.set(event);
                        asyncContext.complete();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event)
                    {
                    }
                });
                asyncContext.setTimeout(0);
                latch.countDown();
            }
        });

        InetSocketAddress address = new InetSocketAddress("localhost", connector.getLocalPort());
        FuturePromise<Session> sessionPromise = new FuturePromise<>();
        client.connect(address, new Session.Listener() {}, sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        Stream stream = session.newStream(frame, null).get(5, TimeUnit.SECONDS);

        // Wait for the server to be in ASYNC_WAIT.
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code));

        if (notify)
            // Wait for the reset to be notified to the async context listener.
            await().atMost(5, TimeUnit.SECONDS).until(() ->
            {
                AsyncEvent asyncEvent = errorAsyncEventRef.get();
                return asyncEvent == null ? null : asyncEvent.getThrowable();
            }, instanceOf(EofException.class));
        else
            // Wait for the reset to NOT be notified to the failure listener.
            await().atMost(5, TimeUnit.SECONDS).during(1, TimeUnit.SECONDS).until(errorAsyncEventRef::get, nullValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testClientResetNotifiesAsyncListener(boolean commitResponse) throws Exception
    {
        CountDownLatch requestLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                if (commitResponse)
                    response.flushBuffer();

                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);

                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onComplete(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onTimeout(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event)
                    {
                        if (!response.isCommitted())
                            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
                        asyncContext.complete();
                        errorLatch.countDown();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event)
                    {
                    }
                });

                requestLatch.countDown();
            }
        });

        Session session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {})
            .get(5, TimeUnit.SECONDS);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Stream stream = session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);

        // Wait for the server to become idle after the request.
        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Reset the stream.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code));

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientResetThenWriteAfterComplete() throws Exception
    {
        AtomicReference<ServletOutputStream> servletOutputRef = new AtomicReference<>();
        CountDownLatch requestLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.flushBuffer();

                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onComplete(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onTimeout(AsyncEvent event)
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event)
                    {
                        asyncContext.complete();
                        errorLatch.countDown();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event)
                    {
                    }
                });
                servletOutputRef.set(response.getOutputStream());
                requestLatch.countDown();
            }
        });

        Session session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {})
            .get(5, TimeUnit.SECONDS);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Stream stream = session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);

        // Wait for the server to become idle after the request.
        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Reset the stream and wait to take effect.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code));
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        // Perform an after completion write.
        assertThrows(IOException.class, () -> servletOutputRef.get().println("write after complete"));
    }
}
