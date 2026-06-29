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

package org.eclipse.jetty.quic.tests;

import java.util.function.Supplier;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;

public class AbstractTest
{
    protected Server server;
    protected SslContextFactory.Server serverTLS;
    protected QuicServerQuicConfiguration serverQuicConfig;
    protected QuicServerConnector serverConnector;
    protected ClientConnector clientConnector;
    private SslContextFactory.Client clientTLS;
    private QuicClientQuicConfiguration clientQuicConfig;
    protected QuicClient quicClient;

    protected void start(Session.Listener.Factory sessionListenerFactory) throws Exception
    {
        prepareServer(sessionListenerFactory);
        server.start();

        prepareClient();
        quicClient.start();
    }

    protected void prepareServer(Session.Listener.Factory sessionListenerFactory)
    {
        prepareServer(() -> new QuicServerConnector(server, serverTLS, serverQuicConfig, sessionListenerFactory));
    }

    protected void prepareServer(ConnectionFactory... connectionFactories)
    {
        prepareServer(() -> new QuicServerConnector(server, serverTLS, serverQuicConfig, connectionFactories));
    }

    private void prepareServer(Supplier<QuicServerConnector> connectorFactory)
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStorePath(MavenPaths.findTestResourceFile("server_keystore.p12"));
        serverTLS.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        httpConfig.addCustomizer(new HostHeaderCustomizer());

        serverQuicConfig = new QuicServerQuicConfiguration();
        serverConnector = connectorFactory.get();
        server.addConnector(serverConnector);
    }

    protected void prepareClient()
    {
        clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
//        clientConnector.setByteBufferPool(new ArrayByteBufferPool.Tracking());
        clientTLS = new SslContextFactory.Client(true);
        clientConnector.setSslContextFactory(clientTLS);
        clientQuicConfig = new QuicClientQuicConfiguration();
        quicClient = new QuicClient(clientQuicConfig, clientConnector);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(quicClient);
        LifeCycle.stop(server);
//        System.err.println(((ArrayByteBufferPool.Tracking)client.getClientConnector().getByteBufferPool()).dumpLeaks());
    }
}
