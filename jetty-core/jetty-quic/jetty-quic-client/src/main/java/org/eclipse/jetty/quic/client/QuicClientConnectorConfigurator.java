//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.quic.common.QuicConfiguration;

/**
 * <p>A QUIC specific {@link ClientConnector.Configurator}.</p>
 * <p>Since QUIC is based on UDP, this class creates {@link DatagramChannel}s instead of
 * {@link SocketChannel}s, and {@link DatagramChannelEndPoint}s instead of
 * {@link SocketChannelEndPoint}s.</p>
 *
 * @see QuicConfiguration
 */
public class QuicClientConnectorConfigurator extends ClientConnector.Configurator
{
    private final QuicConfiguration configuration = new QuicConfiguration();
    private final UnaryOperator<Connection> configurator;

    public QuicClientConnectorConfigurator()
    {
        this(UnaryOperator.identity());
    }

    public QuicClientConnectorConfigurator(UnaryOperator<Connection> configurator)
    {
        this.configurator = Objects.requireNonNull(configurator);
        // Initialize to sane defaults for a client.
        configuration.setSessionRecvWindow(16 * 1024 * 1024);
        configuration.setBidirectionalStreamRecvWindow(8 * 1024 * 1024);
        configuration.setDisableActiveMigration(true);
        configuration.setVerifyPeerCertificates(true);
    }

    public QuicConfiguration getQuicConfiguration()
    {
        return configuration;
    }

    @Override
    public boolean isIntrinsicallySecure(ClientConnector clientConnector, SocketAddress address)
    {
        return true;
    }

    @Override
    public ChannelWithAddress newChannelWithAddress(ClientConnector clientConnector, SocketAddress address, Map<String, Object> context) throws IOException
    {
        context.put(QuicConfiguration.CONTEXT_KEY, configuration);
        DatagramChannel channel = DatagramChannel.open();
        if (clientConnector.getBindAddress() == null)
        {
            // QUIC must know the local address for connection migration, so we must always bind early.
            channel.bind(null);
        }
        return new ChannelWithAddress(channel, address);
    }

    @Override
    public EndPoint newEndPoint(ClientConnector clientConnector, SocketAddress address, SelectableChannel selectable, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new DatagramChannelEndPoint((DatagramChannel)selectable, selector, selectionKey, clientConnector.getScheduler());
    }

    @Override
    public Connection newConnection(ClientConnector clientConnector, SocketAddress address, EndPoint endPoint, Map<String, Object> context)
    {
        return configurator.apply(new ClientQuicConnection(clientConnector, endPoint, context));
    }
}
