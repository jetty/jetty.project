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

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;

public class AbstractQuicTest
{
    protected Server server;
    protected QuicServerConnector connector;
    protected QuicClient client;

    protected void start(Session.Listener.Factory sessionListenerFactory) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12"));
        sslServer.setKeyStorePassword("storepwd");

        QuicServerQuicConfiguration serverQuicConfiguration = new QuicServerQuicConfiguration();

        connector = new QuicServerConnector(server, sslServer, serverQuicConfiguration, sessionListenerFactory);
        server.addConnector(connector);
        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        client = new QuicClient(new QuicClientQuicConfiguration(), clientConnector);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }
}
