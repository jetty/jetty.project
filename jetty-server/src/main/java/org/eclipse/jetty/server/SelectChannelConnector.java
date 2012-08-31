//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>Implementation of {@link NetworkConnector} based on NIO classes.</p>
 */
@ManagedObject("HTTP connector using NIO ByteChannels and Selectors")
public class SelectChannelConnector extends AbstractNetworkConnector
{
    private final SelectorManager _manager;
    private volatile ServerSocketChannel _acceptChannel;
    private volatile boolean _inheritChannel = false;
    private volatile int _localPort = -1;
    private volatile int _acceptQueueSize = 128;
    private volatile boolean _reuseAddress = true;
    private volatile int _lingerTime = -1;

    public SelectChannelConnector(Server server)
    {
        this(server, null);
    }

    public SelectChannelConnector(Server server, SslContextFactory sslContextFactory)
    {
        this(server, null, null, null, sslContextFactory, 0, 0);
    }

    /**
     * @param server    The server this connector will be added to. Must not be null.
     * @param executor  An executor for this connector or null to use the servers executor
     * @param scheduler A scheduler for this connector or null to use the servers scheduler
     * @param pool      A buffer pool for this connector or null to use a default {@link ByteBufferPool}
     * @param acceptors the number of acceptor threads to use, or 0 for a default value.
     */
    public SelectChannelConnector(
            @Name("server") Server server,
            @Name("executor") Executor executor,
            @Name("scheduler") Scheduler scheduler,
            @Name("bufferPool") ByteBufferPool pool,
            @Name("sslContextFactory") SslContextFactory sslContextFactory,
            @Name("acceptors") int acceptors,
            @Name("selectors") int selectors)
    {
        super(server, executor, scheduler, pool, sslContextFactory, acceptors);
        _manager = new ConnectorSelectorManager(selectors > 0 ? selectors : Math.max(1, (Runtime.getRuntime().availableProcessors()) / 4));
        addBean(_manager, true);

        // TODO: why we need to set the linger time when in SSL mode ?
        if (sslContextFactory != null)
            setSoLingerTime(30000);

        // TODO: we hardcode HTTP, but this is a generic connector that should not hardcode anything
        setDefaultConnectionFactory(new HttpServerConnectionFactory(this));
    }

    @Override
    public boolean isOpen()
    {
        ServerSocketChannel channel = _acceptChannel;
        return channel!=null && channel.isOpen();
    }

    /**
     * @return whether this connector uses a channel inherited from the JVM.
     * @see System#inheritedChannel()
     */
    public boolean isInheritChannel()
    {
        return _inheritChannel;
    }

    /**
     * <p>Sets whether this connector uses a channel inherited from the JVM.</p>
     * <p>If true, the connector first tries to inherit from a channel provided by the system.
     * If there is no inherited channel available, or if the inherited channel is not usable,
     * then it will fall back using {@link ServerSocketChannel}.</p>
     * <p>Use it with xinetd/inetd, to launch an instance of Jetty on demand. The port
     * used to access pages on the Jetty instance is the same as the port used to
     * launch Jetty.</p>
     *
     * @param inheritChannel whether this connector uses a channel inherited from the JVM.
     */
    public void setInheritChannel(boolean inheritChannel)
    {
        _inheritChannel = inheritChannel;
    }

    @Override
    public void open() throws IOException
    {
        if (_acceptChannel == null)
        {
            ServerSocketChannel serverChannel = null;
            if (isInheritChannel())
            {
                Channel channel = System.inheritedChannel();
                if (channel instanceof ServerSocketChannel)
                    serverChannel = (ServerSocketChannel)channel;
                else
                    LOG.warn("Unable to use System.inheritedChannel() [{}]. Trying a new ServerSocketChannel at {}:{}", channel, getHost(), getPort());
            }

            if (serverChannel == null)
            {
                serverChannel = ServerSocketChannel.open();

                InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
                serverChannel.socket().bind(bindAddress, getAcceptQueueSize());
                serverChannel.socket().setReuseAddress(getReuseAddress());

                _localPort = serverChannel.socket().getLocalPort();
                if (_localPort <= 0)
                    throw new IOException("Server channel not bound");

                addBean(serverChannel);
            }

            serverChannel.configureBlocking(true);
            addBean(serverChannel);

            _acceptChannel = serverChannel;
        }
    }

    @Override
    public <C> Future<C> shutdown(C c)
    {
        // TODO shutdown all the connections
        return super.shutdown(c);
    }

    @Override
    public void close()
    {
        ServerSocketChannel serverChannel = _acceptChannel;
        _acceptChannel = null;

        if (serverChannel != null)
        {
            removeBean(serverChannel);

            // If the interrupt did not close it, we should close it
            if (serverChannel.isOpen())
            {
                try
                {
                    serverChannel.close();
                }
                catch (IOException e)
                {
                    LOG.warn(e);
                }
            }
        }
        // super.close();
        _localPort = -2;
    }

    @Override
    public void accept(int acceptorID) throws IOException
    {
        ServerSocketChannel serverChannel = _acceptChannel;
        if (serverChannel != null && serverChannel.isOpen())
        {
            SocketChannel channel = serverChannel.accept();
            channel.configureBlocking(false);
            Socket socket = channel.socket();
            configure(socket);
            _manager.accept(channel);
        }
    }

    protected void configure(Socket socket)
    {
        try
        {
            socket.setTcpNoDelay(true);
            if (_lingerTime >= 0)
                socket.setSoLinger(true, _lingerTime / 1000);
            else
                socket.setSoLinger(false, 0);
        }
        catch (SocketException e)
        {
            LOG.ignore(e);
        }
    }

    public SelectorManager getSelectorManager()
    {
        return _manager;
    }

    @Override
    public Object getTransport()
    {
        return _acceptChannel;
    }

    @Override
    public int getLocalPort()
    {
        return _localPort;
    }

    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        return new SelectChannelEndPoint(channel, selectSet, key, getScheduler(), getIdleTimeout());
    }

    protected void endPointClosed(EndPoint endpoint)
    {
        connectionClosed(endpoint.getConnection());
    }

    protected Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment)
    {
        SslContextFactory sslContextFactory = getSslContextFactory();
        if (sslContextFactory != null)
        {
            SSLEngine engine = sslContextFactory.newSSLEngine(endPoint.getRemoteAddress());
            engine.setUseClientMode(false);

            SslConnection sslConnection = new SslConnection(getByteBufferPool(), getExecutor(), endPoint, engine);

            EndPoint appEndPoint = sslConnection.getDecryptedEndPoint();
            Connection connection = getDefaultConnectionFactory().newConnection(channel, appEndPoint, attachment);
            appEndPoint.setConnection(connection);
            connection.onOpen();

            return sslConnection;
        }
        else
        {
            return getDefaultConnectionFactory().newConnection(channel, endPoint, attachment);
        }
    }

    /**
     * @return the linger time
     * @see Socket#getSoLinger()
     */
    public int getLingerTime()
    {
        return _lingerTime;
    }

    /**
     * @param lingerTime the linger time. Use -1 to disable.
     * @see Socket#setSoLinger(boolean, int)
     */
    public void setSoLingerTime(int lingerTime)
    {
        _lingerTime = lingerTime;
    }

    /**
     * @return the accept queue size
     */
    public int getAcceptQueueSize()
    {
        return _acceptQueueSize;
    }

    /**
     * @param acceptQueueSize the accept queue size (also known as accept backlog)
     */
    public void setAcceptQueueSize(int acceptQueueSize)
    {
        _acceptQueueSize = acceptQueueSize;
    }

    /**
     * @return whether the server socket reuses addresses
     * @see ServerSocket#getReuseAddress()
     */
    public boolean getReuseAddress()
    {
        return _reuseAddress;
    }

    /**
     * @param reuseAddress whether the server socket reuses addresses
     * @see ServerSocket#setReuseAddress(boolean)
     */
    public void setReuseAddress(boolean reuseAddress)
    {
        _reuseAddress = reuseAddress;
    }

    private final class ConnectorSelectorManager extends SelectorManager
    {
        private ConnectorSelectorManager(int selectors)
        {
            super(selectors);
        }

        @Override
        protected void execute(Runnable task)
        {
            getExecutor().execute(task);
        }

        @Override
        public void connectionOpened(Connection connection)
        {
            SelectChannelConnector.this.connectionOpened(connection);
            super.connectionOpened(connection);
        }

        @Override
        public void connectionClosed(Connection connection)
        {
            super.connectionClosed(connection);
            SelectChannelConnector.this.connectionClosed(connection);
        }

        @Override
        public void connectionUpgraded(EndPoint endpoint, Connection oldConnection)
        {
            SelectChannelConnector.this.connectionUpgraded(oldConnection, endpoint.getConnection());
            super.connectionUpgraded(endpoint, oldConnection);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
        {
            return SelectChannelConnector.this.newEndPoint(channel, selectSet, selectionKey);
        }

        @Override
        public Connection newConnection(SocketChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            return SelectChannelConnector.this.newConnection(channel, endpoint, attachment);
        }
    }

}
