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

package org.eclipse.jetty.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.channels.Channel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.EventListener;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * This {@link Connector} implementation is the primary connector for the
 * Jetty server over TCP/IP.  By the use of various {@link ConnectionFactory} instances it is able
 * to accept connections for HTTP, HTTP/2 and WebSocket, either directly or over SSL.
 * <p>
 * The connector is a fully asynchronous NIO based implementation that by default will
 * use all the commons services (eg {@link Executor}, {@link Scheduler})  of the
 * passed {@link Server} instance, but all services may also be constructor injected
 * into the connector so that it may operate with dedicated or otherwise shared services.
 * </p>
 * <h2>Connection Factories</h2>
 * <p>
 * Various convenience constructors are provided to assist with common configurations of
 * ConnectionFactories, whose generic use is described in {@link AbstractConnector}.
 * If no connection factories are passed, then the connector will
 * default to use a {@link HttpConnectionFactory}.  If an non null {@link SslContextFactory}
 * instance is passed, then this used to instantiate a {@link SslConnectionFactory} which is
 * prepended to the other passed or default factories.
 * </p>
 * <h2>Selectors</h2>
 * <p>
 * The default number of selectors is equal to half of the number of processors available to the JVM,
 * which should allow optimal performance even if all the connections used are performing
 * significant non-blocking work in the callback tasks.
 * </p>
 */
@ManagedObject("HTTP connector using NIO ByteChannels and Selectors")
public class ServerConnector extends AbstractNetworkConnector
{
    private final SelectorManager _manager;
    private final AtomicReference<Closeable> _acceptor = new AtomicReference<>();
    private volatile ServerSocketChannel _acceptChannel;
    private volatile boolean _inheritChannel = false;
    private volatile int _localPort = -1;
    private volatile int _acceptQueueSize = 0;
    private volatile boolean _reuseAddress = true;
    private volatile boolean _reusePort = false;
    private volatile boolean _acceptedTcpNoDelay = true;
    private volatile int _acceptedReceiveBufferSize = -1;
    private volatile int _acceptedSendBufferSize = -1;

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     *
     * @param server The {@link Server} this connector will accept connection for.
     */
    public ServerConnector(
        @Name("server") Server server)
    {
        this(server, null, null, null, -1, -1, new HttpConnectionFactory());
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     *
     * @param server The {@link Server} this connector will accept connection for.
     * @param acceptors the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     * the selector threads are used to accept connections.
     * @param selectors the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors)
    {
        this(server, null, null, null, acceptors, selectors, new HttpConnectionFactory());
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     *
     * @param server The {@link Server} this connector will accept connection for.
     * @param acceptors the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     * the selector threads are used to accept connections.
     * @param selectors the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, acceptors, selectors, factories);
    }

    /**
     * <p>Construct a Server Connector with the passed Connection factories.</p>
     *
     * @param server The {@link Server} this connector will accept connection for.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, -1, -1, factories);
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the primary protocol</p>.
     *
     * @param server The {@link Server} this connector will accept connection for.
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the
     * list of HTTP Connection Factory.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory.Server sslContextFactory)
    {
        this(server, null, null, null, -1, -1, AbstractConnectionFactory.getFactories(sslContextFactory, new HttpConnectionFactory()));
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the primary protocol</p>.
     *
     * @param server The {@link Server} this connector will accept connection for.
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the
     * list of HTTP Connection Factory.
     * @param acceptors the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     * the selector threads are used to accept connections.
     * @param selectors the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors,
        @Name("sslContextFactory") SslContextFactory.Server sslContextFactory)
    {
        this(server, null, null, null, acceptors, selectors, AbstractConnectionFactory.getFactories(sslContextFactory, new HttpConnectionFactory()));
    }

    /**
     * @param server The {@link Server} this connector will accept connection for.
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the
     * list of ConnectionFactories, with the first factory being the default protocol for the SslConnectionFactory.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory.Server sslContextFactory,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, -1, -1, AbstractConnectionFactory.getFactories(sslContextFactory, factories));
    }

    /**
     * @param server The server this connector will be accept connection for.
     * @param executor An executor used to run tasks for handling requests, acceptors and selectors.
     * If null then use the servers executor
     * @param scheduler A scheduler used to schedule timeouts. If null then use the servers scheduler
     * @param bufferPool A ByteBuffer pool used to allocate buffers.  If null then create a private pool with default configuration.
     * @param acceptors the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     * the selector threads are used to accept connections.
     * @param selectors the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("executor") Executor executor,
        @Name("scheduler") Scheduler scheduler,
        @Name("bufferPool") ByteBufferPool bufferPool,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, acceptors, factories);
        _manager = newSelectorManager(getExecutor(), getScheduler(), selectors);
        installBean(_manager, true);
        setAcceptorPriorityDelta(-2);
    }

    protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
    {
        return new ServerConnectorManager(executor, scheduler, selectors);
    }

    @Override
    protected void doStart() throws Exception
    {
        addBean(_acceptChannel);

        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            _manager.addEventListener(l);

        super.doStart();

        if (getAcceptors() == 0)
        {
            _acceptChannel.configureBlocking(false);
            _acceptor.set(_manager.acceptor(_acceptChannel));
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        _acceptor.set(null);

        super.doStop();

        removeBean(_acceptChannel);
        _acceptChannel = null;

        for (EventListener l : getBeans(EventListener.class))
        {
            _manager.removeEventListener(l);
        }
    }

    @Override
    public boolean isOpen()
    {
        ServerSocketChannel channel = _acceptChannel;
        return channel != null && channel.isOpen();
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
     * @see ServerConnector#openAcceptChannel()
     */
    public void setInheritChannel(boolean inheritChannel)
    {
        _inheritChannel = inheritChannel;
    }

    /**
     * Open the connector using the passed ServerSocketChannel.
     * This open method can be called before starting the connector to pass it a ServerSocketChannel
     * that will be used instead of one returned from {@link #openAcceptChannel()}
     *
     * @param acceptChannel the channel to use
     * @throws IOException if the server channel is not bound
     */
    public void open(ServerSocketChannel acceptChannel) throws IOException
    {
        if (isStarted())
            throw new IllegalStateException(getState());
        _acceptChannel = acceptChannel;
        _acceptChannel.configureBlocking(true);
        _localPort = _acceptChannel.socket().getLocalPort();
        if (_localPort <= 0)
            throw new IOException("Server channel not bound");
    }

    @Override
    public void open() throws IOException
    {
        if (_acceptChannel == null)
        {
            open(openAcceptChannel());
            super.open();
        }
    }

    /**
     * Called by {@link #open()} to obtain the accepting channel.
     *
     * @return ServerSocketChannel used to accept connections.
     * @throws IOException if unable to obtain or configure the server channel
     */
    protected ServerSocketChannel openAcceptChannel() throws IOException
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
            InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
            serverChannel = ServerSocketChannel.open();
            setSocketOption(serverChannel, StandardSocketOptions.SO_REUSEADDR, getReuseAddress());
            setSocketOption(serverChannel, StandardSocketOptions.SO_REUSEPORT, isReusePort());
            try
            {
                serverChannel.bind(bindAddress, getAcceptQueueSize());
            }
            catch (Throwable e)
            {
                IO.close(serverChannel);
                throw new IOException("Failed to bind to " + bindAddress, e);
            }
        }

        return serverChannel;
    }

    private <T> void setSocketOption(ServerSocketChannel channel, SocketOption<T> option, T value)
    {
        try
        {
            channel.setOption(option, value);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not configure {} to {} on {}", option, value, channel, x);
        }
    }

    @Override
    public void close()
    {
        super.close();

        // When acceptors == 0, we want the ServerSocketChannel
        // to be closed by the SelectorManager when the
        // SelectorManager is stopped (as a bean) in doStop().
        if (getAcceptors() > 0)
            IO.close(_acceptChannel);

        _localPort = -2;
    }

    @Override
    public void accept(int acceptorID) throws IOException
    {
        ServerSocketChannel serverChannel = _acceptChannel;
        if (serverChannel != null && serverChannel.isOpen())
        {
            SocketChannel channel = serverChannel.accept();
            accepted(channel);
        }
    }

    private void accepted(SocketChannel channel) throws IOException
    {
        channel.configureBlocking(false);
        Socket socket = channel.socket();
        configure(socket);
        _manager.accept(channel);
    }

    protected void configure(Socket socket)
    {
        try
        {
            socket.setTcpNoDelay(_acceptedTcpNoDelay);
            if (_acceptedReceiveBufferSize > -1)
                socket.setReceiveBufferSize(_acceptedReceiveBufferSize);
            if (_acceptedSendBufferSize > -1)
                socket.setSendBufferSize(_acceptedSendBufferSize);
        }
        catch (SocketException e)
        {
            LOG.trace("IGNORED", e);
        }
    }

    @ManagedAttribute("The Selector Manager")
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
    @ManagedAttribute("local port")
    public int getLocalPort()
    {
        return _localPort;
    }

    protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        SocketChannelEndPoint endpoint = new SocketChannelEndPoint(channel, selectSet, key, getScheduler());
        endpoint.setIdleTimeout(getIdleTimeout());
        return endpoint;
    }

    /**
     * @return the accept queue size
     */
    @ManagedAttribute("Accept Queue size")
    public int getAcceptQueueSize()
    {
        return _acceptQueueSize;
    }

    /**
     * Set the accept queue size (also known as accept backlog).
     * @param acceptQueueSize the accept queue size (also known as accept backlog)
     */
    public void setAcceptQueueSize(int acceptQueueSize)
    {
        _acceptQueueSize = acceptQueueSize;
    }

    /**
     * @return whether rebinding the server socket is allowed with sockets in tear-down states
     * @see ServerSocket#getReuseAddress()
     */
    @ManagedAttribute("Server Socket SO_REUSEADDR")
    public boolean getReuseAddress()
    {
        return _reuseAddress;
    }

    /**
     * @param reuseAddress whether rebinding the server socket is allowed with sockets in tear-down states
     * @see ServerSocket#setReuseAddress(boolean)
     */
    public void setReuseAddress(boolean reuseAddress)
    {
        _reuseAddress = reuseAddress;
    }

    /**
     * @return whether it is allowed to bind multiple server sockets to the same host and port
     */
    @ManagedAttribute("Server Socket SO_REUSEPORT")
    public boolean isReusePort()
    {
        return _reusePort;
    }

    /**
     * Set whether it is allowed to bind multiple server sockets to the same host and port.
     * @param reusePort whether it is allowed to bind multiple server sockets to the same host and port
     */
    public void setReusePort(boolean reusePort)
    {
        _reusePort = reusePort;
    }

    /**
     * @return whether the accepted socket gets {@link java.net.SocketOptions#TCP_NODELAY TCP_NODELAY} enabled.
     * @see Socket#getTcpNoDelay()
     */
    @ManagedAttribute("Accepted Socket TCP_NODELAY")
    public boolean getAcceptedTcpNoDelay()
    {
        return _acceptedTcpNoDelay;
    }

    /**
     * @param tcpNoDelay whether {@link java.net.SocketOptions#TCP_NODELAY TCP_NODELAY} gets enabled on the the accepted socket.
     * @see Socket#setTcpNoDelay(boolean)
     */
    public void setAcceptedTcpNoDelay(boolean tcpNoDelay)
    {
        this._acceptedTcpNoDelay = tcpNoDelay;
    }

    /**
     * @return the {@link java.net.SocketOptions#SO_RCVBUF SO_RCVBUF} size to set onto the accepted socket.
     * A value of -1 indicates that it is left to its default value.
     * @see Socket#getReceiveBufferSize()
     */
    @ManagedAttribute("Accepted Socket SO_RCVBUF")
    public int getAcceptedReceiveBufferSize()
    {
        return _acceptedReceiveBufferSize;
    }

    /**
     * @param receiveBufferSize the {@link java.net.SocketOptions#SO_RCVBUF SO_RCVBUF} size to set onto the accepted socket.
     * A value of -1 indicates that it is left to its default value.
     * @see Socket#setReceiveBufferSize(int)
     */
    public void setAcceptedReceiveBufferSize(int receiveBufferSize)
    {
        this._acceptedReceiveBufferSize = receiveBufferSize;
    }

    /**
     * @return the {@link java.net.SocketOptions#SO_SNDBUF SO_SNDBUF} size to set onto the accepted socket.
     * A value of -1 indicates that it is left to its default value.
     * @see Socket#getSendBufferSize()
     */
    @ManagedAttribute("Accepted Socket SO_SNDBUF")
    public int getAcceptedSendBufferSize()
    {
        return _acceptedSendBufferSize;
    }

    /**
     * @param sendBufferSize the {@link java.net.SocketOptions#SO_SNDBUF SO_SNDBUF} size to set onto the accepted socket.
     * A value of -1 indicates that it is left to its default value.
     * @see Socket#setSendBufferSize(int)
     */
    public void setAcceptedSendBufferSize(int sendBufferSize)
    {
        this._acceptedSendBufferSize = sendBufferSize;
    }

    @Override
    public void setAccepting(boolean accepting)
    {
        super.setAccepting(accepting);
        if (getAcceptors() > 0)
            return;

        try
        {
            if (accepting)
            {
                if (_acceptor.get() == null)
                {
                    Closeable acceptor = _manager.acceptor(_acceptChannel);
                    if (!_acceptor.compareAndSet(null, acceptor))
                        acceptor.close();
                }
            }
            else
            {
                Closeable acceptor = _acceptor.get();
                if (acceptor != null && _acceptor.compareAndSet(acceptor, null))
                    acceptor.close();
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    protected class ServerConnectorManager extends SelectorManager
    {
        public ServerConnectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected void accepted(SelectableChannel channel) throws IOException
        {
            ServerConnector.this.accepted((SocketChannel)channel);
        }

        @Override
        protected SocketChannelEndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            return ServerConnector.this.newEndPoint((SocketChannel)channel, selector, selectionKey);
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            return getDefaultConnectionFactory().newConnection(ServerConnector.this, endpoint);
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
    }
}
