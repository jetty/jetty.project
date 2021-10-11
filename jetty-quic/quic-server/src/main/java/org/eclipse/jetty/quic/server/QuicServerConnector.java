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
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
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
    private final ServerDatagramSelectorManager _manager;
    private final SslContextFactory.Server _sslContextFactory;
    private final QuicheConfig _quicheConfig = new QuicheConfig();
    private volatile DatagramChannel _datagramChannel;
    private volatile int _localPort = -1;

    public QuicServerConnector(Server server, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        this(server, null, null, null, sslContextFactory, factories);
    }

    public QuicServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, SslContextFactory.Server sslContextFactory, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        _manager = new ServerDatagramSelectorManager(getExecutor(), getScheduler(), 1);
        addBean(_manager);
        _sslContextFactory = sslContextFactory;
        addBean(_sslContextFactory);
    }

    @Override
    public int getLocalPort()
    {
        return _localPort;
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = _datagramChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    protected void doStart() throws Exception
    {
        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            _manager.addEventListener(l);
        super.doStart();
        _manager.accept(_datagramChannel);

        String alias = _sslContextFactory.getCertAlias();
        char[] keyStorePassword = _sslContextFactory.getKeyStorePassword().toCharArray();
        String keyManagerPassword = _sslContextFactory.getKeyManagerPassword();
        SSLKeyPair keyPair = new SSLKeyPair(
            _sslContextFactory.getKeyStoreResource().getFile(),
            _sslContextFactory.getKeyStoreType(),
            keyStorePassword,
            alias == null ? "mykey" : alias,
            keyManagerPassword == null ? keyStorePassword : keyManagerPassword.toCharArray()
        );
        File[] pemFiles = keyPair.export(new File(System.getProperty("java.io.tmpdir")));

        // TODO: make the QuicheConfig configurable.
        _quicheConfig.setPrivKeyPemPath(pemFiles[0].getPath());
        _quicheConfig.setCertChainPemPath(pemFiles[1].getPath());
        _quicheConfig.setVerifyPeer(false);
        // Idle timeouts must not be managed by Quiche.
        _quicheConfig.setMaxIdleTimeout(0L);
        _quicheConfig.setInitialMaxData(10000000L);
        _quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        _quicheConfig.setInitialMaxStreamDataBidiRemote(10000000L);
        _quicheConfig.setInitialMaxStreamDataUni(10000000L);
        _quicheConfig.setInitialMaxStreamsUni(100L);
        _quicheConfig.setInitialMaxStreamsBidi(100L);
        _quicheConfig.setCongestionControl(QuicheConfig.CongestionControl.RENO);
        List<String> protocols = getProtocols();
        // This is only needed for Quiche example clients.
        protocols.add(0, "http/0.9");
        _quicheConfig.setApplicationProtos(protocols.toArray(String[]::new));
    }

    @Override
    public void open() throws IOException
    {
        if (_datagramChannel == null)
        {
            _datagramChannel = openDatagramChannel();
            _datagramChannel.configureBlocking(false);
            _localPort = _datagramChannel.socket().getLocalPort();
            if (_localPort <= 0)
                throw new IOException("DatagramChannel not bound");
            addBean(_datagramChannel);
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
        _manager.setIdleTimeout(idleTimeout);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        for (EventListener l : getBeans(EventListener.class))
            _manager.removeEventListener(l);
    }

    @Override
    public void close()
    {
        super.close();
        DatagramChannel datagramChannel = _datagramChannel;
        _datagramChannel = null;
        if (datagramChannel != null)
        {
            removeBean(datagramChannel);
            IO.close(datagramChannel);
        }
        _localPort = -2;
    }

    @Override
    public Object getTransport()
    {
        return _datagramChannel;
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
            return new ServerQuicConnection(QuicServerConnector.this, endpoint, _quicheConfig);
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
