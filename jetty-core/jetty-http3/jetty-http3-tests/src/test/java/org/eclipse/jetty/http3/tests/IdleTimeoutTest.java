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

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientQuicConfiguration;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IdleTimeoutTest
{
    private Server server;

    @BeforeEach
    public void prepare()
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testIdleTimeoutWhenCongested() throws Exception
    {
        long idleTimeout = 1000;

        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath("src/test/resources/keystore.p12");
        sslServer.setKeyStorePassword("storepwd");
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        AtomicBoolean established = new AtomicBoolean();
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        RawHTTP3ServerConnectionFactory h3 = new RawHTTP3ServerConnectionFactory(new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session.Server session)
            {
                established.set(true);
            }

            @Override
            public void onDisconnect(Session session, long error, String reason)
            {
                disconnectLatch.countDown();
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        QuicServerConnector connector = new QuicServerConnector(server, sslServer, serverQuicConfig, h3);
        // TODO: port this test from Quiche to Quic.
//        {
//            @Override
//            protected Connection newConnection(EndPoint endpoint)
//            {
//                QuicheServerConnectionFactory q = (QuicheServerConnectionFactory)getDefaultConnectionFactory();
//                return new ServerQuicConnection(this, getSslContextFactory(), getServerQuicConfiguration(), endpoint, q.getSessionListenerFactory())
//                {
//                    @Override
//                    protected ServerQuicheSession newServerQuicSession(Quiche quiche, SocketAddress remoteAddress)
//                    {
//                        return new ServerQuicSession(getConnector(), getServerQuicConfiguration(), quiche, this, getEndPoint().getLocalSocketAddress(), remoteAddress, getSessionListenerFactory().newListener())
//                        {
//                            @Override
//                            protected int data(QuicheStream stream, boolean last, ByteBuffer buffer) throws IOException
//                            {
//                                if (stream.isBidirectional() && established.get())
//                                    return 0;
//                                return super.data(stream, last, buffer);
//                            }
//
//                            @Override
//                            public void disconnect(long appError, String reason, Throwable failure, Callback callback)
//                            {
//                                closeLatch.countDown();
//                                super.disconnect(appError, reason, failure, callback);
//                            }
//                        };
//                    }
//                };
//            }
//        };
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);
        server.start();

        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        try (HTTP3Client http3Client = new HTTP3Client(clientQuicConfig))
        {
            http3Client.getClientConnector().setSslContextFactory(new SslContextFactory.Client(true));
            http3Client.start();

            Session.Client session = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> http3Client.connect(new QuicTransport(new QuicClient(clientQuicConfig)), new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {}, p));

            MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:" + connector.getLocalPort() + "/path"), HttpVersion.HTTP_3, HttpFields.EMPTY);
            // The request will complete exceptionally.
            session.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener() {}, Promise.Invocable.noop());

            assertTrue(closeLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
            assertTrue(disconnectLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        }
    }
}
