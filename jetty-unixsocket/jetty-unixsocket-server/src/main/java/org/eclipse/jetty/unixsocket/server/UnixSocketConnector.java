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

package org.eclipse.jetty.unixsocket.server;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executor;

import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.unixsocket.common.UnixSocketEndPoint;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A server-side connector for UNIX sockets.</p>
 *
 * @deprecated Use UnixDomainServerConnector from the jetty-unixdomain-server module instead (requires Java 16 or later).
 */
@Deprecated(forRemoval = true)
@ManagedObject("Connector using UNIX Socket")
public class UnixSocketConnector extends AbstractConnector
{
    // See SockAddrUnix.ADDR_LENGTH.
    public static final int MAX_UNIX_SOCKET_PATH_LENGTH = 107;
    private static final Logger LOG = LoggerFactory.getLogger(UnixSocketConnector.class);

    private final SelectorManager _manager;
    private String _unixSocket = "/tmp/jetty.sock";
    private volatile UnixServerSocketChannel _acceptChannel;
    private volatile int _acceptQueueSize = 0;
    private volatile boolean _reuseAddress = true;

    /**
     * <p>Constructs a UnixSocketConnector with the default configuration.</p>
     *
     * @param server the {@link Server} this connector will accept connections for.
     */
    public UnixSocketConnector(@Name("server") Server server)
    {
        this(server, -1);
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given number of selectors</p>
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param selectors the number of selectors, or &lt;=0 for a default value.
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("selectors") int selectors)
    {
        this(server, selectors, new HttpConnectionFactory());
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given ConnectionFactories.</p>
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param factories zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("factories") ConnectionFactory... factories)
    {
        this(server, -1, factories);
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given selectors and ConnectionFactories.</p>
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param selectors the number of selectors, or &lt;=0 for a default value.
     * @param factories zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("selectors") int selectors, @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, selectors, factories);
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given SslContextFactory.</p>
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param sslContextFactory when non null a {@link SslConnectionFactory} prepended to the other ConnectionFactories
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("sslContextFactory") SslContextFactory.Server sslContextFactory)
    {
        this(server, -1, sslContextFactory);
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given selectors and SslContextFactory.</p>.
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param sslContextFactory when non null a {@link SslConnectionFactory} prepended to the other ConnectionFactories
     * @param selectors the number of selectors, or &lt;=0 for a default value.
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("selectors") int selectors, @Name("sslContextFactory") SslContextFactory.Server sslContextFactory)
    {
        this(server, null, null, null, selectors, AbstractConnectionFactory.getFactories(sslContextFactory, new HttpConnectionFactory()));
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given SslContextFactory and ConnectionFactories.</p>.
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param sslContextFactory when non null a {@link SslConnectionFactory} prepended to the other ConnectionFactories
     * @param factories zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("sslContextFactory") SslContextFactory.Server sslContextFactory, @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, -1, AbstractConnectionFactory.getFactories(sslContextFactory, factories));
    }

    /**
     * <p>Constructs a UnixSocketConnector with the given parameters.</p>.
     *
     * @param server the {@link Server} this connector will accept connections for.
     * @param executor the executor that runs tasks for handling requests, acceptors and selectors.
     * @param scheduler the scheduler used to schedule timed tasks.
     * @param bufferPool the ByteBufferPool used to allocate buffers.
     * @param selectors the number of selectors, or &lt;=0 for a default value.
     * @param factories zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(@Name("server") Server server, @Name("executor") Executor executor, @Name("scheduler") Scheduler scheduler, @Name("bufferPool") ByteBufferPool bufferPool, @Name("selectors") int selectors, @Name("factories") ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        _manager = newSelectorManager(getExecutor(), getScheduler(), selectors > 0 ? selectors : 1);
        addBean(_manager, true);
    }

    @ManagedAttribute("The UNIX socket file name")
    public String getUnixSocket()
    {
        return _unixSocket;
    }

    public void setUnixSocket(String filename)
    {
        if (filename.length() > MAX_UNIX_SOCKET_PATH_LENGTH)
            throw new IllegalArgumentException("Unix socket path too long");
        _unixSocket = filename;
    }

    protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
    {
        return new UnixSocketConnectorManager(executor, scheduler, selectors);
    }

    @Override
    protected void doStart() throws Exception
    {
        open();
        super.doStart();
        if (getAcceptors() == 0)
            _manager.acceptor(_acceptChannel);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        close();
    }

    public void open() throws IOException
    {
        if (_acceptChannel == null)
        {
            File file = new File(_unixSocket);
            file.deleteOnExit();
            SocketAddress bindAddress = new UnixSocketAddress(file);
            UnixServerSocketChannel serverChannel = UnixServerSocketChannel.open();
            serverChannel.configureBlocking(getAcceptors() > 0);
            try
            {
                serverChannel.socket().bind(bindAddress, getAcceptQueueSize());
            }
            catch (IOException e)
            {
                LOG.warn("cannot bind {} exists={} writable={}", file, file.exists(), file.canWrite());
                throw e;
            }
            addBean(serverChannel);
            if (LOG.isDebugEnabled())
                LOG.debug("opened {}", serverChannel);
            _acceptChannel = serverChannel;
        }
    }

    public void close()
    {
        UnixServerSocketChannel serverChannel = _acceptChannel;
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
                    LOG.warn("Unable to close serverChannel: {}", serverChannel, e);
                }
            }

            try
            {
                Files.deleteIfExists(Paths.get(_unixSocket));
            }
            catch (IOException e)
            {
                LOG.warn("Unable to delete UnixSocket at {}", _unixSocket, e);
            }
        }
    }

    @Override
    public void accept(int acceptorID) throws IOException
    {
        LOG.debug("Blocking UnixSocket accept used.  Might not be able to be interrupted!");
        UnixServerSocketChannel serverChannel = _acceptChannel;
        if (serverChannel != null && serverChannel.isOpen())
        {
            LOG.debug("accept {}", serverChannel);
            UnixSocketChannel channel = serverChannel.accept();
            LOG.debug("accepted {}", channel);
            accepted(channel);
        }
    }

    protected void accepted(UnixSocketChannel channel) throws IOException
    {
        channel.configureBlocking(false);
        _manager.accept(channel);
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

    protected UnixSocketEndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
    {
        return new UnixSocketEndPoint((UnixSocketChannel)channel, selector, key, getScheduler());
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
    @ManagedAttribute("Whether the server socket reuses addresses")
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

    @Override
    public String toString()
    {
        return String.format("%s{%s}", super.toString(), _unixSocket);
    }

    protected class UnixSocketConnectorManager extends SelectorManager
    {
        public UnixSocketConnectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected void accepted(SelectableChannel channel) throws IOException
        {
            UnixSocketConnector.this.accepted((UnixSocketChannel)channel);
        }

        @Override
        protected Selector newSelector() throws IOException
        {
            return NativeSelectorProvider.getInstance().openSelector();
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            UnixSocketEndPoint endPoint = UnixSocketConnector.this.newEndPoint(channel, selector, selectionKey);
            endPoint.setIdleTimeout(getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            return getDefaultConnectionFactory().newConnection(UnixSocketConnector.this, endpoint);
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

        @Override
        protected boolean doFinishConnect(SelectableChannel channel) throws IOException
        {
            return ((UnixSocketChannel)channel).finishConnect();
        }

        @Override
        protected boolean isConnectionPending(SelectableChannel channel)
        {
            return ((UnixSocketChannel)channel).isConnectionPending();
        }

        @Override
        protected SelectableChannel doAccept(SelectableChannel server) throws IOException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("doAccept async {}", server);
            UnixSocketChannel channel = ((UnixServerSocketChannel)server).accept();
            if (LOG.isDebugEnabled())
                LOG.debug("accepted async {}", channel);
            return channel;
        }
    }
}
