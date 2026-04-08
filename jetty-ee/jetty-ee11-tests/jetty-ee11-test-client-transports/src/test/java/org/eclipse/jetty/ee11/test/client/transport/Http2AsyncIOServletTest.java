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

package org.eclipse.jetty.ee11.test.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.CloseState;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Http2AsyncIOServletTest
{
    private final HttpConfiguration httpConfig = new HttpConfiguration();
    private Server server;
    private ServerConnector connector;
    private HTTP2Client client;

    private void start(HttpServlet httpServlet) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory(httpConfig));
        server.addConnector(connector);
        ServletContextHandler servletContextHandler = new ServletContextHandler("/");
        servletContextHandler.addServlet(new ServletHolder(httpServlet), "/*");
        server.setHandler(servletContextHandler);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HTTP2Client();
        client.setExecutor(clientThreads);
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
        {
            // Wait for the reset to be notified to the async context listener.
            await().atMost(5, TimeUnit.SECONDS).until(() ->
            {
                AsyncEvent asyncEvent = errorAsyncEventRef.get();
                return asyncEvent == null ? null : asyncEvent.getThrowable();
            }, instanceOf(EofException.class));
        }
        else
        {
            // Wait for the reset to NOT be notified to the failure listener.
            await().atMost(5, TimeUnit.SECONDS).during(1, TimeUnit.SECONDS).until(errorAsyncEventRef::get, nullValue());
        }
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSessionCloseWithPendingRequestThenReset(boolean useReaderWriter) throws Exception
    {
        // Disable output aggregation for Servlets, so each byte is echoed back.
        httpConfig.setOutputAggregationSize(0);
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    if (useReaderWriter)
                        request.getReader().transferTo(response.getWriter());
                    else
                        request.getInputStream().transferTo(response.getOutputStream());
                }
                catch (Throwable x)
                {
                    serverFailureLatch.countDown();
                    throw x;
                }
            }
        });

        HTTP2Session session = (HTTP2Session)client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {})
            .get(5, TimeUnit.SECONDS);
        Queue<Stream.Data> dataList = new ConcurrentLinkedQueue<>();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Stream stream = session.newStream(new HeadersFrame(request, null, false), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }
                    dataList.offer(data);
                    if (data.frame().isEndStream())
                        return;
                }
            }
        }).get(5, TimeUnit.SECONDS);

        stream.data(new DataFrame(stream.getId(), UTF_8.encode("Hello Jetty"), false))
            .get(5, TimeUnit.SECONDS);
        stream.demand();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !dataList.isEmpty());

        // Initiates graceful close, waits for the streams to finish as per specification.
        session.close(ErrorCode.NO_ERROR.code, "client_close", Callback.NOOP);

        // Finish the pending stream, either by resetting or sending the last frame.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code));

        // The server should see the effects of the reset.
        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        // The session must eventually be closed.
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.getCloseState() == CloseState.CLOSED);
        // The endPoint must eventually be closed.
        await().atMost(5, TimeUnit.SECONDS).until(() -> !session.getEndPoint().isOpen());

        // Cleanup.
        dataList.forEach(Stream.Data::release);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSessionCloseWithPendingRequestServerIdleTimeout(boolean useReaderWriter) throws Exception
    {
        // Disable output aggregation for Servlets, so each byte is echoed back.
        httpConfig.setOutputAggregationSize(0);
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    if (useReaderWriter)
                        request.getReader().transferTo(response.getWriter());
                    else
                        request.getInputStream().transferTo(response.getOutputStream());
                }
                catch (Throwable x)
                {
                    serverFailureLatch.countDown();
                    throw x;
                }
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HTTP2Session session = (HTTP2Session)client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {})
            .get(5, TimeUnit.SECONDS);
        Queue<Stream.Data> dataList = new ConcurrentLinkedQueue<>();
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Stream stream = session.newStream(new HeadersFrame(request, null, false), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }
                    dataList.offer(data);
                    if (data.frame().isEndStream())
                        return;
                }
            }
        }).get(5, TimeUnit.SECONDS);

        stream.data(new DataFrame(stream.getId(), UTF_8.encode("Hello Jetty"), false))
            .get(5, TimeUnit.SECONDS);
        stream.demand();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !dataList.isEmpty());

        // Initiates graceful close, waits for the streams to finish as per specification.
        session.close(ErrorCode.NO_ERROR.code, "client_close", Callback.NOOP);

        // Do not finish the streams, the server must idle timeout.
        assertTrue(serverFailureLatch.await(2 * idleTimeout, TimeUnit.SECONDS));
        // The session must eventually be closed.
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.getCloseState() == CloseState.CLOSED);
        // The endPoint must eventually be closed.
        await().atMost(5, TimeUnit.SECONDS).until(() -> !session.getEndPoint().isOpen());

        // Cleanup.
        dataList.forEach(Stream.Data::release);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testSessionCloseWithPendingRequestThenClientDisconnectThenServerIdleTimeout(boolean useReaderWriter) throws Exception
    {
        AtomicReference<Thread> serverThreadRef = new AtomicReference<>();
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    serverThreadRef.set(Thread.currentThread());
                    if (useReaderWriter)
                        request.getReader().transferTo(response.getWriter());
                    else
                        request.getInputStream().transferTo(response.getOutputStream());
                }
                catch (Throwable x)
                {
                    serverFailureLatch.countDown();
                    throw x;
                }
            }
        });
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        HTTP2Session session = (HTTP2Session)client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {})
            .get(5, TimeUnit.SECONDS);
        MetaData.Request request = new MetaData.Request("POST", HttpURI.from("/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        Stream stream = session.newStream(new HeadersFrame(request, null, false), new Stream.Listener() {}).get(5, TimeUnit.SECONDS);

        stream.data(new DataFrame(stream.getId(), UTF_8.encode("Hello Jetty"), false))
            .get(5, TimeUnit.SECONDS);
        stream.demand();

        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread serverThread = serverThreadRef.get();
            return serverThread != null && serverThread.getState() == Thread.State.WAITING;
        });

        // Initiates graceful close, then immediately disconnect.
        session.close(ErrorCode.NO_ERROR.code, "client_close", Callback.from(session::disconnect));

        // Do not finish the streams, the server must idle timeout.
        assertTrue(serverFailureLatch.await(2 * idleTimeout, TimeUnit.SECONDS));
        // The session must eventually be closed.
        await().atMost(5, TimeUnit.SECONDS).until(() -> session.getCloseState() == CloseState.CLOSED);
        // The endPoint must eventually be closed.
        await().atMost(5, TimeUnit.SECONDS).until(() -> !session.getEndPoint().isOpen());
    }
}
