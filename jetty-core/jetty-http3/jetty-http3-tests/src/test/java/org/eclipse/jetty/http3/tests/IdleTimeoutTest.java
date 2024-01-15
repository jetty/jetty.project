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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
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
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.HTTP3ServerConnector;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.server.ServerQuicConnection;
import org.eclipse.jetty.quic.server.ServerQuicSession;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class IdleTimeoutTest
{
    private Server server;
    private HTTP3Client http3Client;

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
        LifeCycle.stop(http3Client);
        LifeCycle.stop(server);
    }

    @Test
    public void testIdleTimeoutWhenCongested(WorkDir workDir) throws Exception
    {
        long idleTimeout = 1000;
        AtomicBoolean established = new AtomicBoolean();
        CountDownLatch disconnectLatch = new CountDownLatch(1);
        RawHTTP3ServerConnectionFactory h3 = new RawHTTP3ServerConnectionFactory(new HttpConfiguration(), new Session.Server.Listener()
        {
            @Override
            public void onAccept(Session session)
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
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        HTTP3ServerConnector connector = new HTTP3ServerConnector(server, sslContextFactory, h3)
        {
            @Override
            protected ServerQuicConnection newConnection(EndPoint endpoint)
            {
                return new ServerQuicConnection(this, endpoint)
                {
                    @Override
                    protected ServerQuicSession newQuicSession(SocketAddress remoteAddress, QuicheConnection quicheConnection)
                    {
                        return new ServerQuicSession(getExecutor(), getScheduler(), getByteBufferPool(), quicheConnection, this, remoteAddress, getQuicServerConnector())
                        {
                            @Override
                            public int flush(long streamId, ByteBuffer buffer, boolean last) throws IOException
                            {
                                if (established.get())
                                    return 0;
                                return super.flush(streamId, buffer, last);
                            }

                            @Override
                            public void outwardClose(long error, String reason)
                            {
                                closeLatch.countDown();
                                super.outwardClose(error, reason);
                            }
                        };
                    }
                };
            }
        };
        connector.getQuicConfiguration().setPemWorkDirectory(workDir.getEmptyPathDir());
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);
        server.start();

        http3Client = new HTTP3Client();
        http3Client.getClientConnector().setSslContextFactory(new SslContextFactory.Client(true));
        http3Client.start();

        Session.Client session = http3Client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost:" + connector.getLocalPort() + "/path"), HttpVersion.HTTP_3, HttpFields.EMPTY);
        // The request will complete exceptionally.
        session.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener() {});

        assertTrue(closeLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(disconnectLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }
}
