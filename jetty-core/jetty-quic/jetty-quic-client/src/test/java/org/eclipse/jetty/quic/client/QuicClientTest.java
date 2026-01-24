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

package org.eclipse.jetty.quic.client;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QuicClientTest
{
    private Server server;
    private QuicheServerConnector connector;
    private QuicClient client;

    private void startServer(Path workDir, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        httpConfig.addCustomizer(new HostHeaderCustomizer());

        QuicheServerQuicConfiguration serverQuicConfig = new QuicheServerQuicConfiguration(workDir);
        connector = new QuicheServerConnector(server, sslContextFactory, serverQuicConfig, new HttpConnectionFactory(httpConfig));
        connector.setPort(8443);
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    private void startClient() throws Exception
    {
        QuicClientQuicConfiguration quicConfig = new QuicClientQuicConfiguration();
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setByteBufferPool(new ArrayByteBufferPool.Tracking());
        client = new QuicClient(quicConfig, clientConnector);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
        ArrayByteBufferPool.Tracking byteBufferPool = (ArrayByteBufferPool.Tracking)client.getClientConnector().getByteBufferPool();
        Set<ArrayByteBufferPool.Tracking.TrackedBuffer> clientLeaks = byteBufferPool.getLeaks();
        assertEquals(0, clientLeaks.size(), byteBufferPool.dumpLeaks());
    }

    @Test
    public void testServerOnly(@TempDir Path workDir) throws Exception
    {
        startServer(workDir, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        new CountDownLatch(1).await();
    }

    @Test
    public void testClientOnly() throws Exception
    {
        startClient();

        Promise.Completable<Session> completable = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", 8443), new Session.Listener() {}, completable);
        Session session = completable.get(555, TimeUnit.SECONDS);
        assertNotNull(session);
    }

    @Test
    public void testConnect(@TempDir Path workDir) throws Exception
    {
        startServer(workDir, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        startClient();

        Promise.Completable<Session> completable = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener() {}, completable);
        Session session = completable.get(555, TimeUnit.SECONDS);
        assertNotNull(session);
    }
}
