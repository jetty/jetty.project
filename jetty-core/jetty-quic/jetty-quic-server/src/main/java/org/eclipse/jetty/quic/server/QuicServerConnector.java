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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EventListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicSessionContainer;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A server side network connector that uses a {@link DatagramChannel} to listen on a network port for QUIC traffic.</p>
 * <p>This connector uses {@link ConnectionFactory}s to configure the protocols to be transported by QUIC.
 * The protocol is negotiated during the connection establishment by {@link QuicSession}, and for each QUIC stream
 * managed by a {@link QuicSession} a {@link ConnectionFactory} is used to create a {@link Connection} for the
 * correspondent {@link QuicStreamEndPoint}.</p>
 *
 * @see ServerQuicConfiguration
 */
public class QuicServerConnector extends AbstractNetworkConnector
{
    private final QuicSessionContainer container = new QuicSessionContainer();
    private final ServerDatagramSelectorManager selectorManager;
    private final QuicServerConnectionFactory connectionFactory;
    private volatile DatagramChannel datagramChannel;
    private volatile int localPort = -1;

    /**
     * @param server the {@link Server}
     * @param sslContextFactory the {@link SslContextFactory.Server}
     * @param factories the {@link ConnectionFactory}s of the protocols transported by QUIC
     * @deprecated use {@link #QuicServerConnector(Server, ServerQuicConfiguration, ConnectionFactory...)} instead
     */
    @Deprecated(since = "12.0.7", forRemoval = true)
    public QuicServerConnector(Server server, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        this(server, new ServerQuicConfiguration(sslContextFactory, null), factories);
    }

    public QuicServerConnector(Server server, ServerQuicConfiguration quicConfiguration, ConnectionFactory... factories)
    {
        this(server, null, null, null, quicConfiguration, factories);
    }

    /**
     * @param server the {@link Server}
     * @param executor the {@link Executor}
     * @param scheduler the {@link Scheduler}
     * @param bufferPool the {@link ByteBufferPool}
     * @param sslContextFactory the {@link SslContextFactory.Server}
     * @param factories the {@link ConnectionFactory}s of the protocols transported by QUIC
     * @deprecated use {@link #QuicServerConnector(Server, Executor, Scheduler, ByteBufferPool, ServerQuicConfiguration, ConnectionFactory...)} instead
     */
    @Deprecated(since = "12.0.7", forRemoval = true)
    public QuicServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        this(server, executor, scheduler, bufferPool, new ServerQuicConfiguration(sslContextFactory, null), factories);
    }

    public QuicServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, ServerQuicConfiguration quicConfiguration, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        this.selectorManager = new ServerDatagramSelectorManager(getExecutor(), getScheduler(), 1);
        this.connectionFactory = new QuicServerConnectionFactory(quicConfiguration);
    }

    public ServerQuicConfiguration getQuicConfiguration()
    {
        return connectionFactory.getQuicConfiguration();
    }

    @Override
    public int getLocalPort()
    {
        return localPort;
    }

    public int getInputBufferSize()
    {
        return getQuicConfiguration().getInputBufferSize();
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        getQuicConfiguration().setInputBufferSize(inputBufferSize);
    }

    public int getOutputBufferSize()
    {
        return getQuicConfiguration().getOutputBufferSize();
    }

    public void setOutputBufferSize(int outputBufferSize)
    {
        getQuicConfiguration().setOutputBufferSize(outputBufferSize);
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return getQuicConfiguration().isUseInputDirectByteBuffers();
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        getQuicConfiguration().setUseInputDirectByteBuffers(useInputDirectByteBuffers);
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return getQuicConfiguration().isUseOutputDirectByteBuffers();
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        getQuicConfiguration().setUseOutputDirectByteBuffers(useOutputDirectByteBuffers);
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = datagramChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    protected void doStart() throws Exception
    {
        addBean(container);
        addBean(selectorManager);
        addBean(connectionFactory);

        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            selectorManager.addEventListener(l);

        connectionFactory.getQuicConfiguration().setPemWorkDirectory(findPemWorkDirectory());

        super.doStart();

        selectorManager.accept(datagramChannel);
    }

    private Path findPemWorkDirectory()
    {
        Path pemWorkDirectory = getQuicConfiguration().getPemWorkDirectory();
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
    public void open() throws IOException
    {
        if (datagramChannel == null)
        {
            datagramChannel = openDatagramChannel();
            datagramChannel.configureBlocking(false);
            localPort = datagramChannel.socket().getLocalPort();
            if (localPort <= 0)
                throw new IOException("DatagramChannel not bound");
            addBean(datagramChannel);
        }
    }

    protected DatagramChannel openDatagramChannel() throws IOException
    {
        InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
        DatagramChannel datagramChannel = DatagramChannel.open();
        try
        {
            datagramChannel.bind(bindAddress);
            return datagramChannel;
        }
        catch (Throwable e)
        {
            IO.close(datagramChannel);
            throw new IOException("Failed to bind to " + bindAddress, e);
        }
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        selectorManager.setIdleTimeout(idleTimeout);
    }

    @Override
    protected void doStop() throws Exception
    {
        // We want the DatagramChannel to be stopped by the SelectorManager.
        super.doStop();

        removeBean(datagramChannel);
        datagramChannel = null;
        localPort = -2;

        removeBean(connectionFactory);

        for (EventListener l : getBeans(EventListener.class))
            selectorManager.removeEventListener(l);
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return container.shutdown();
    }

    @Override
    public Object getTransport()
    {
        return datagramChannel;
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no accept mechanism");
    }

    protected EndPoint newEndPoint(DatagramChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new DatagramChannelEndPoint(channel, selector, selectionKey, getScheduler());
    }

    protected ServerQuicConnection newConnection(EndPoint endpoint)
    {
        return connectionFactory.newConnection(QuicServerConnector.this, endpoint);
    }

    private class ServerDatagramSelectorManager extends SelectorManager
    {
        protected ServerDatagramSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            EndPoint endPoint = QuicServerConnector.this.newEndPoint((DatagramChannel)channel, selector, selectionKey);
            endPoint.setIdleTimeout(getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            return QuicServerConnector.this.newConnection(endpoint);
        }

        @Override
        protected void endPointOpened(EndPoint endpoint)
        {
            super.endPointOpened(endpoint);
            onEndPointOpened(endpoint);
        }

        @Override
        protected void endPointClosed(EndPoint endpoint)
        {
            onEndPointClosed(endpoint);
            super.endPointClosed(endpoint);
        }

        private void setIdleTimeout(long idleTimeout)
        {
            getConnectedEndPoints().forEach(endPoint -> endPoint.setIdleTimeout(idleTimeout));
        }
    }
}
