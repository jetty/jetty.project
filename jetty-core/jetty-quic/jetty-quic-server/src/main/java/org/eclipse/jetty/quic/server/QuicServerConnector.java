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
import java.security.KeyStore;
import java.util.EventListener;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicSessionContainer;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.quiche.PemExporter;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A server side network connector that uses a {@link DatagramChannel} to listen on a network port for QUIC traffic.</p>
 * <p>This connector uses {@link ConnectionFactory}s to configure the protocols to support.
 * The protocol is negotiated during the connection establishment by {@link QuicSession}, and for each QUIC stream
 * managed by a {@link QuicSession} a {@link ConnectionFactory} is used to create a {@link Connection} for the
 * correspondent {@link QuicStreamEndPoint}.</p>
 *
 * @see QuicConfiguration
 */
public class QuicServerConnector extends AbstractNetworkConnector
{
    private final QuicConfiguration quicConfiguration = new QuicConfiguration();
    private final QuicSessionContainer container = new QuicSessionContainer();
    private final ServerDatagramSelectorManager selectorManager;
    private final SslContextFactory.Server sslContextFactory;
    private Path privateKeyPemPath;
    private Path certificateChainPemPath;
    private Path trustedCertificatesPemPath;
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
        addBeanFromConstructor(this.selectorManager);
        this.sslContextFactory = sslContextFactory;
        addBeanFromConstructor(this.sslContextFactory);
        addBeanFromConstructor(quicConfiguration);
        addBeanFromConstructor(container);
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

        Set<String> aliases = sslContextFactory.getAliases();
        if (aliases.isEmpty())
            throw new IllegalStateException("Missing or invalid KeyStore: a SslContextFactory configured with a valid, non-empty KeyStore is required");
        String alias = sslContextFactory.getCertAlias();
        if (alias == null)
            alias = aliases.stream().findFirst().orElseThrow();
        String keyManagerPassword = sslContextFactory.getKeyManagerPassword();
        char[] password = keyManagerPassword == null ? sslContextFactory.getKeyStorePassword().toCharArray() : keyManagerPassword.toCharArray();
        KeyStore keyStore = sslContextFactory.getKeyStore();
        Path certificateWorkPath = findPemWorkDirectory();
        Path[] keyPair = PemExporter.exportKeyPair(keyStore, alias, password, certificateWorkPath);
        privateKeyPemPath = keyPair[0];
        certificateChainPemPath = keyPair[1];
        KeyStore trustStore = sslContextFactory.getTrustStore();
        if (trustStore != null)
            trustedCertificatesPemPath = PemExporter.exportTrustStore(trustStore, certificateWorkPath);
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

    QuicheConfig newQuicheConfig()
    {
        QuicheConfig quicheConfig = new QuicheConfig();
        quicheConfig.setPrivKeyPemPath(privateKeyPemPath.toString());
        quicheConfig.setCertChainPemPath(certificateChainPemPath.toString());
        quicheConfig.setTrustedCertsPemPath(trustedCertificatesPemPath == null ? null : trustedCertificatesPemPath.toString());
        quicheConfig.setVerifyPeer(sslContextFactory.getNeedClientAuth() || sslContextFactory.getWantClientAuth());
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
        return quicheConfig;
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
        deleteFile(privateKeyPemPath);
        privateKeyPemPath = null;
        deleteFile(certificateChainPemPath);
        certificateChainPemPath = null;
        deleteFile(trustedCertificatesPemPath);
        trustedCertificatesPemPath = null;

        // We want the DatagramChannel to be stopped by the SelectorManager.
        super.doStop();

        removeBean(datagramChannel);
        datagramChannel = null;
        localPort = -2;

        for (EventListener l : getBeans(EventListener.class))
            selectorManager.removeEventListener(l);
    }

    private void deleteFile(Path file)
    {
        try
        {
            if (file != null)
                Files.delete(file);
        }
        catch (IOException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not delete {}", file, x);
        }
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
        return new ServerQuicConnection(QuicServerConnector.this, endpoint);
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
            ServerQuicConnection connection = QuicServerConnector.this.newConnection(endpoint);
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
