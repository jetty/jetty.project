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

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientQuicConfiguration;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.quic.quiche.client.QuicheClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.client.QuicheTransport;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.http3.api.Session.Client;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(WorkDirExtension.class)
public class Http3AsyncIOServletTest
{
    public WorkDir workDir;
    private final HttpConfiguration httpConfig = new HttpConfiguration();
    private Server server;
    private QuicheServerConnector connector;
    private HTTP3Client client;

    private void start(HttpServlet httpServlet) throws Exception
    {
        server = new Server();
        SslContextFactory.Server serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        serverSslContextFactory.setKeyStorePassword("storepwd");
        QuicheServerQuicConfiguration serverQuicConfiguration = HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(workDir.getEmptyPathDir()));
        connector = new QuicheServerConnector(server, serverSslContextFactory, serverQuicConfiguration, new HTTP3ServerConnectionFactory(httpConfig));
        server.addConnector(connector);
        ServletContextHandler servletContextHandler = new ServletContextHandler("/");
        servletContextHandler.addServlet(new ServletHolder(httpServlet), "/*");
        server.setHandler(servletContextHandler);
        server.start();

        QuicheClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicheClientQuicConfiguration());
        client = new HTTP3Client(clientQuicConfig);
        client.getClientConnector().setSslContextFactory(new SslContextFactory.Client(true));
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
        Client session = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> client.connect(new QuicheTransport((QuicheClientQuicConfiguration)client.getClientQuicConfiguration()), address, new Client.Listener() {}, p));
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from("/"), HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(frame, null, p));

        // Wait for the server to be in ASYNC_WAIT.
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);

        stream.disconnect(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), new Exception(), Promise.Invocable.noop());

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

    @Test
    public void testClientResetNotifiesAsyncListener()
    {
        // See the equivalent test in Http2AsyncIOServletTest for HTTP/2.
        // For HTTP/3 we do not have a "reset" event that we can relay to applications,
        // because HTTP/3 does not have a "reset" frame; QUIC has RESET_STREAM, but we
        // do not have an event from Quiche to reliably report it to applications.
        assumeTrue(false);
    }
}
