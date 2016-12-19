//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.Callback;
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
 * <h2>Connection Factories</h2>
 * Various convenience constructors are provided to assist with common configurations of
 * ConnectionFactories, whose generic use is described in {@link AbstractConnector}.
 * If no connection factories are passed, then the connector will
 * default to use a {@link HttpConnectionFactory}.  If an non null {@link SslContextFactory}
 * instance is passed, then this used to instantiate a {@link SslConnectionFactory} which is
 * prepended to the other passed or default factories.
 * <h2>Selectors</h2>
 * The connector will use the {@link Executor} service to execute a number of Selector Tasks,
 * which are implemented to each use a NIO {@link Selector} instance to asynchronously
 * schedule a set of accepted connections.  It is the selector thread that will call the
 * {@link Callback} instances passed in the {@link EndPoint#fillInterested(Callback)} or
 * {@link EndPoint#write(Callback, java.nio.ByteBuffer...)} methods.  It is expected
 * that these callbacks may do some non-blocking IO work, but will always dispatch to the
 * {@link Executor} service any blocking, long running or application tasks.
 * <p>
 * The default number of selectors is equal to the number of processors available to the JVM,
 * which should allow optimal performance even if all the connections used are performing
 * significant non-blocking work in the callback tasks.
 */
@ManagedObject("HTTP connector using NIO ByteChannels and Selectors")
public class ServerConnector extends AbstractNetworkConnector
{
    private final SelectorManager _manager;
    private volatile ServerSocketChannel _acceptChannel;
    private volatile boolean _inheritChannel = false;
    private volatile int _localPort = -1;
    private volatile int _acceptQueueSize = 0;
    private volatile boolean _reuseAddress = true;
    private volatile int _lingerTime = -1;

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     * @param server The {@link Server} this connector will accept connection for.
     */
    public ServerConnector(
        @Name("server") Server server)
    {
        this(server,null,null,null,-1,-1,new HttpConnectionFactory());
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     * @param server The {@link Server} this connector will accept connection for.
     * @param acceptors
     *          the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     *          the selector threads are used to accept connections.
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors)
    {
        this(server,null,null,null,acceptors,selectors,new HttpConnectionFactory());
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     * @param server The {@link Server} this connector will accept connection for.
     * @param acceptors
     *          the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     *          the selector threads are used to accept connections.
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server,null,null,null,acceptors,selectors,factories);
    }

    /**
     * <p>Construct a Server Connector with the passed Connection factories.</p>
     * @param server The {@link Server} this connector will accept connection for.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server,null,null,null,-1,-1,factories);
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the primary protocol</p>.
     * @param server The {@link Server} this connector will accept connection for.
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the
     * list of HTTP Connection Factory.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,-1,-1,AbstractConnectionFactory.getFactories(sslContextFactory,new HttpConnectionFactory()));
    }

    /**
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the primary protocol</p>.
     * @param server The {@link Server} this connector will accept connection for.
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the
     * list of HTTP Connection Factory.
     * @param acceptors
     *          the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     *          the selector threads are used to accept connections.
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("acceptors") int acceptors,
        @Name("selectors") int selectors,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,acceptors,selectors,AbstractConnectionFactory.getFactories(sslContextFactory,new HttpConnectionFactory()));
    }

    /**
     * @param server The {@link Server} this connector will accept connection for.
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the
     * list of ConnectionFactories, with the first factory being the default protocol for the SslConnectionFactory.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public ServerConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, -1, -1, AbstractConnectionFactory.getFactories(sslContextFactory, factories));
    }

    /**
     * @param server
     *          The server this connector will be accept connection for.
     * @param executor
     *          An executor used to run tasks for handling requests, acceptors and selectors.
     *          If null then use the servers executor
     * @param scheduler
     *          A scheduler used to schedule timeouts. If null then use the servers scheduler
     * @param bufferPool
     *          A ByteBuffer pool used to allocate buffers.  If null then create a private pool with default configuration.
     * @param acceptors
     *          the number of acceptor threads to use, or -1 for a default value. Acceptors accept new TCP/IP connections.  If 0, then
     *          the selector threads are used to accept connections.
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     * @param factories
     *          Zero or more {@link ConnectionFactory} instances used to create and configure connections.
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
        super(server,executor,scheduler,bufferPool,acceptors,factories);
        _manager = newSelectorManager(getExecutor(), getScheduler(),
            selectors>0?selectors:Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2)));
        addBean(_manager, true);
        setAcceptorPriorityDelta(-2);
    }

    protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
    {
        return new ServerConnectorManager(executor, scheduler, selectors);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (getAcceptors()==0)
        {
            _acceptChannel.configureBlocking(false);
            _manager.acceptor(_acceptChannel);
        }
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
                serverChannel.socket().setReuseAddress(getReuseAddress());
                serverChannel.socket().bind(bindAddress, getAcceptQueueSize());

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
    public Future<Void> shutdown()
    {
        // shutdown all the connections
        return super.shutdown();
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
    @ManagedAttribute("local port")
    public int getLocalPort()
    {
        return _localPort;
    }

    protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        SocketChannelEndPoint endpoint = new SocketChannelEndPoint(channel, selectSet, key, getScheduler());
        endpoint.setIdleTimeout(getIdleTimeout());
        return endpoint;
    }

    /**
     * @return the linger time
     * @see Socket#getSoLinger()
     */
    @ManagedAttribute("TCP/IP solinger time or -1 to disable")
    public int getSoLingerTime()
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
    @ManagedAttribute("Accept Queue size")
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
        protected ChannelEndPoint newEndPoint(SelectableChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
        {
            return ServerConnector.this.newEndPoint((SocketChannel)channel, selectSet, selectionKey);
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
