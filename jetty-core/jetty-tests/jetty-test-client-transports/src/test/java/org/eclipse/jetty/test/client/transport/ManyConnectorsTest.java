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

package org.eclipse.jetty.test.client.transport;

import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;

import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ManyConnectorsTest
{
    private Server server = new Server();

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    public void testManyConnectors(int acceptors) throws Exception
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        ServerConnector connector1 = new ServerConnector(server, acceptors, 1, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector1);

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        QuicServerConnector connector2 = new QuicServerConnector(server, sslContextFactory, serverQuicConfig, new HTTP3ServerConnectionFactory(httpConfig));
        server.addConnector(connector2);

        connector1.addEventListener(new NetworkConnector.Listener()
        {
            @Override
            public void onOpen(NetworkConnector connector)
            {
                connector2.setPort(connector.getLocalPort());
            }
        });

        server.start();

        // Make sure both connectors are on the same port.
        assertEquals(connector1.getLocalPort(), connector2.getLocalPort());

        ServerSocketChannel serverSocketChannel = (ServerSocketChannel)connector1.getTransport();
        int beanSize1 = connector1.getBeans().size();

        DatagramChannel datagramChannel = (DatagramChannel)connector2.getTransport();
        int beanSize2 = connector2.getBeans().size();

        // Test that stop+start works and does not leak beans.

        server.stop();

        assertFalse(serverSocketChannel.isOpen());
        assertFalse(connector1.contains(serverSocketChannel));

        assertFalse(datagramChannel.isOpen());
        assertFalse(connector2.contains(datagramChannel));

        server.start();

        assertEquals(connector1.getLocalPort(), connector2.getLocalPort());
        assertEquals(beanSize1, connector1.getBeans().size());
        assertEquals(beanSize2, connector2.getBeans().size());
    }
}
