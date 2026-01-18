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

package org.eclipse.jetty.quic.server;

import java.nio.channels.DatagramChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.common.SessionContainer;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.DatagramServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/// A server side network connector that uses a [DatagramChannel] to listen on a network port for QUIC traffic.
///
/// This connector uses [ConnectionFactory]s to configure the application protocols to be transported by QUIC.
/// The application protocol is negotiated during the connection establishment by [QuicSession], and for each
/// [QuicStream] managed by a `QuicSession` a [ConnectionFactory] for the negotiated protocol is used to create
/// a [Connection] for the correspondent [StreamEndPoint].
///
/// @see QuicServerQuicConfiguration
public class QuicServerConnector extends DatagramServerConnector
{
    private final SessionContainer container = new SessionContainer();
    private final QuicServerConnectionFactory connectionFactory;

    public QuicServerConnector(Server server, SslContextFactory.Server sslContextFactory, QuicServerQuicConfiguration quicConfiguration, ConnectionFactory... factories)
    {
        this(server, null, null, null, sslContextFactory, quicConfiguration, factories);
    }

    public QuicServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, SslContextFactory.Server sslContextFactory, QuicServerQuicConfiguration quicConfiguration, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, factories);
        this.connectionFactory = new QuicServerConnectionFactory(sslContextFactory, quicConfiguration);
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return connectionFactory.getSslContextFactory();
    }

    public QuicServerQuicConfiguration getServerQuicConfiguration()
    {
        return connectionFactory.getServerQuicConfiguration();
    }

    public int getInputBufferSize()
    {
        return connectionFactory.getInputBufferSize();
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        connectionFactory.setInputBufferSize(inputBufferSize);
    }

    protected void doStart() throws Exception
    {
        addBean(container);
        addBean(connectionFactory);

        connectionFactory.configure(this);
        QuicServerQuicConfiguration quicConfiguration = connectionFactory.getServerQuicConfiguration();
        quicConfiguration.addEventListener(container);

        super.doStart();
    }

    @Override
    public ConnectionFactory getDefaultConnectionFactory()
    {
        return connectionFactory;
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return container.shutdown()
            .handleAsync((r, x) ->
            {
                CompletableFuture<Void> shutdown = super.shutdown();
                LifeCycle.stop(this);
                return shutdown;
            }, getExecutor())
            .thenCompose(Function.identity());
    }
}
