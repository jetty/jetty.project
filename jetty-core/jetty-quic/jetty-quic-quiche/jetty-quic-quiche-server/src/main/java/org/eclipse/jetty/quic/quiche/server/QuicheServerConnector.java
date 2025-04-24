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

package org.eclipse.jetty.quic.quiche.server;

import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.SessionContainer;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.quic.quiche.QuicheSession;
import org.eclipse.jetty.quic.quiche.QuicheStream;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.DatagramServerConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A server side network connector that uses a {@link DatagramChannel} to listen on a network port for QUIC traffic.</p>
 * <p>This connector uses {@link ConnectionFactory}s to configure the application protocols to be transported by QUIC.
 * The application protocol is negotiated during the connection establishment by {@link QuicheSession}, and for each
 * {@link QuicheStream} managed by {@code QuicheSession} a {@code ConnectionFactory} for the negotiated protocol is
 * used to create a {@link Connection} for the correspondent {@link StreamEndPoint}.</p>
 *
 * @see QuicheServerQuicConfiguration
 */
public class QuicheServerConnector extends DatagramServerConnector
{
    private final SessionContainer container = new SessionContainer();
    private final QuicheServerConnectionFactory connectionFactory;

    public QuicheServerConnector(Server server, SslContextFactory.Server sslContextFactory, QuicheServerQuicConfiguration quicConfiguration, ConnectionFactory... factories)
    {
        this(server, null, null, null, sslContextFactory, quicConfiguration, factories);
    }

    public QuicheServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, SslContextFactory.Server sslContextFactory, QuicheServerQuicConfiguration quicConfiguration, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, factories);
        this.connectionFactory = new QuicheServerConnectionFactory(sslContextFactory, quicConfiguration);
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return connectionFactory.getSslContextFactory();
    }

    public QuicheServerQuicConfiguration getServerQuicConfiguration()
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
        QuicheServerQuicConfiguration quicConfiguration = connectionFactory.getServerQuicConfiguration();
        quicConfiguration.addEventListener(container);
        quicConfiguration.setPemWorkDirectory(findPemWorkDirectory());

        super.doStart();
    }

    private Path findPemWorkDirectory()
    {
        Path pemWorkDirectory = getServerQuicConfiguration().getPemWorkDirectory();
        if (pemWorkDirectory != null)
            return pemWorkDirectory;
        String jettyBase = System.getProperty("jetty.base");
        if (jettyBase != null)
        {
            pemWorkDirectory = Path.of(jettyBase).resolve("work");
            if (Files.exists(pemWorkDirectory))
                return pemWorkDirectory;
        }
        throw new IllegalStateException("No PEM work directory configured");
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
