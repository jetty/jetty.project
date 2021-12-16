//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicSessionContainer;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.SSLKeyPair;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

public class QuicServerConnector extends AbstractNetworkConnector
{
    private final QuicConfiguration quicConfiguration = new QuicConfiguration();
    private final QuicSessionContainer container = new QuicSessionContainer();
    private final ServerDatagramSelectorManager selectorManager;
    private final SslContextFactory.Server sslContextFactory;
    private final QuicheConfig quicheConfig = new QuicheConfig();
    private volatile DatagramChannel datagramChannel;
    private volatile int localPort = -1;
    private int inputBufferSize = 2048;
    private int outputBufferSize = 2048;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;

    public QuicServerConnector(Server server, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        this(server, null, null, null, sslContextFactory, factories);
    }

    public QuicServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        this.selectorManager = new ServerDatagramSelectorManager(getExecutor(), getScheduler(), 1);
        addBean(this.selectorManager);
        this.sslContextFactory = sslContextFactory;
        addBean(this.sslContextFactory);
        addBean(quicConfiguration);
        addBean(container);
        // Initialize to sane defaults for a server.
        quicConfiguration.setSessionRecvWindow(4 * 1024 * 1024);
        quicConfiguration.setBidirectionalStreamRecvWindow(2 * 1024 * 1024);
        // One bidirectional stream to simulate the TCP stream, and no unidirectional streams.
        quicConfiguration.setMaxBidirectionalRemoteStreams(1);
        quicConfiguration.setMaxUnidirectionalRemoteStreams(0);
    }

    public QuicConfiguration getQuicConfiguration()
    {
        return quicConfiguration;
    }

    @Override
    public int getLocalPort()
    {
        return localPort;
    }

    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
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
        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            selectorManager.addEventListener(l);
        super.doStart();
        selectorManager.accept(datagramChannel);

        String alias = sslContextFactory.getCertAlias();
        char[] keyStorePassword = sslContextFactory.getKeyStorePassword().toCharArray();
        String keyManagerPassword = sslContextFactory.getKeyManagerPassword();
        SSLKeyPair keyPair = new SSLKeyPair(
            sslContextFactory.getKeyStoreResource().getFile(),
            sslContextFactory.getKeyStoreType(),
            keyStorePassword,
            alias == null ? "mykey" : alias,
            keyManagerPassword == null ? keyStorePassword : keyManagerPassword.toCharArray()
        );
        File[] pemFiles = keyPair.export(new File(System.getProperty("java.io.tmpdir")));

        quicheConfig.setPrivKeyPemPath(pemFiles[0].getPath());
        quicheConfig.setCertChainPemPath(pemFiles[1].getPath());
        quicheConfig.setVerifyPeer(false);
        // Idle timeouts must not be managed by Quiche.
        quicheConfig.setMaxIdleTimeout(0L);
        quicheConfig.setInitialMaxData((long)quicConfiguration.getSessionRecvWindow());
        quicheConfig.setInitialMaxStreamDataBidiLocal((long)quicConfiguration.getBidirectionalStreamRecvWindow());
        quicheConfig.setInitialMaxStreamDataBidiRemote((long)quicConfiguration.getBidirectionalStreamRecvWindow());
        quicheConfig.setInitialMaxStreamDataUni((long)quicConfiguration.getUnidirectionalStreamRecvWindow());
        quicheConfig.setInitialMaxStreamsUni((long)quicConfiguration.getMaxUnidirectionalRemoteStreams());
        quicheConfig.setInitialMaxStreamsBidi((long)quicConfiguration.getMaxBidirectionalRemoteStreams());
        quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.CUBIC);
        List<String> protocols = getProtocols();
        // This is only needed for Quiche example clients.
        protocols.add(0, "http/0.9");
        quicheConfig.setApplicationProtos(protocols.toArray(String[]::new));
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
        }
        catch (Throwable e)
        {
            IO.close(datagramChannel);
            throw new IOException("Failed to bind to " + bindAddress, e);
        }
        return datagramChannel;
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

    private class ServerDatagramSelectorManager extends SelectorManager
    {
        protected ServerDatagramSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            EndPoint endPoint = new DatagramChannelEndPoint((DatagramChannel)channel, selector, selectionKey, getScheduler());
            endPoint.setIdleTimeout(getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            ServerQuicConnection connection = new ServerQuicConnection(QuicServerConnector.this, endpoint, quicheConfig);
            connection.addEventListener(container);
            connection.setInputBufferSize(getInputBufferSize());
            connection.setOutputBufferSize(getOutputBufferSize());
            connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
            connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
            return connection;
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
