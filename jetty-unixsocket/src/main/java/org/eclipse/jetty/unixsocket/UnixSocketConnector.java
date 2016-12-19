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

package org.eclipse.jetty.unixsocket;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

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
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

/**
 *
 */
@ManagedObject("HTTP connector using NIO ByteChannels and Selectors")
public class UnixSocketConnector extends AbstractConnector
{
    private static final Logger LOG = Log.getLogger(UnixSocketConnector.class);
    
    private final SelectorManager _manager;
    private String _unixSocket = "/tmp/jetty.sock";
    private volatile UnixServerSocketChannel _acceptChannel;
    private volatile int _acceptQueueSize = 0;
    private volatile boolean _reuseAddress = true;


    /* ------------------------------------------------------------ */
    /** HTTP Server Connection.
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     * @param server The {@link Server} this connector will accept connection for. 
     */
    public UnixSocketConnector( @Name("server") Server server)
    {
        this(server,null,null,null,-1,new HttpConnectionFactory());
    }
    
    /* ------------------------------------------------------------ */
    /** HTTP Server Connection.
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     * @param server The {@link Server} this connector will accept connection for. 
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("selectors") int selectors)
    {
        this(server,null,null,null,selectors,new HttpConnectionFactory());
    }
    
    /* ------------------------------------------------------------ */
    /** HTTP Server Connection.
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the only factory.</p>
     * @param server The {@link Server} this connector will accept connection for. 
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server,null,null,null,selectors,factories);
    }

    /* ------------------------------------------------------------ */
    /** Generic Server Connection with default configuration.
     * <p>Construct a Server Connector with the passed Connection factories.</p>
     * @param server The {@link Server} this connector will accept connection for. 
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server,null,null,null,-1,factories);
    }

    /* ------------------------------------------------------------ */
    /** HTTP Server Connection.
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the primary protocol</p>.
     * @param server The {@link Server} this connector will accept connection for. 
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the 
     * list of HTTP Connection Factory.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,-1,AbstractConnectionFactory.getFactories(sslContextFactory,new HttpConnectionFactory()));
    }

    /* ------------------------------------------------------------ */
    /** HTTP Server Connection.
     * <p>Construct a ServerConnector with a private instance of {@link HttpConnectionFactory} as the primary protocol</p>.
     * @param server The {@link Server} this connector will accept connection for. 
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the 
     * list of HTTP Connection Factory.
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value. Selectors notice and schedule established connection that can make IO progress.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("selectors") int selectors,
        @Name("sslContextFactory") SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,selectors,AbstractConnectionFactory.getFactories(sslContextFactory,new HttpConnectionFactory()));
    }

    /* ------------------------------------------------------------ */
    /** Generic SSL Server Connection.
     * @param server The {@link Server} this connector will accept connection for. 
     * @param sslContextFactory If non null, then a {@link SslConnectionFactory} is instantiated and prepended to the 
     * list of ConnectionFactories, with the first factory being the default protocol for the SslConnectionFactory.
     * @param factories Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("sslContextFactory") SslContextFactory sslContextFactory,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, -1, AbstractConnectionFactory.getFactories(sslContextFactory, factories));
    }

    /** Generic Server Connection.
     * @param server    
     *          The server this connector will be accept connection for.  
     * @param executor  
     *          An executor used to run tasks for handling requests, acceptors and selectors.
     *          If null then use the servers executor
     * @param scheduler 
     *          A scheduler used to schedule timeouts. If null then use the servers scheduler
     * @param bufferPool
     *          A ByteBuffer pool used to allocate buffers.  If null then create a private pool with default configuration.
     * @param selectors
     *          the number of selector threads, or &lt;=0 for a default value(1). Selectors notice and schedule established connection that can make IO progress.
     * @param factories 
     *          Zero or more {@link ConnectionFactory} instances used to create and configure connections.
     */
    public UnixSocketConnector(
        @Name("server") Server server,
        @Name("executor") Executor executor,
        @Name("scheduler") Scheduler scheduler,
        @Name("bufferPool") ByteBufferPool bufferPool,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        super(server,executor,scheduler,bufferPool,0,factories);
        _manager = newSelectorManager(getExecutor(), getScheduler(),
            selectors>0?selectors:1);
        addBean(_manager, true);
        setAcceptorPriorityDelta(-2);
    }

    @ManagedAttribute
    public String getUnixSocket()
    {
        return _unixSocket;
    }
    
    public void setUnixSocket(String filename)
    {
        _unixSocket=filename;
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
        
        if (getAcceptors()==0)
            _manager.acceptor(_acceptChannel);
    }
    
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        close();
    }

    public boolean isOpen()
    {
        UnixServerSocketChannel channel = _acceptChannel;
        return channel!=null && channel.isOpen();
    }

    
    public void open() throws IOException
    {
        if (_acceptChannel == null)
        {
            UnixServerSocketChannel serverChannel = UnixServerSocketChannel.open();
            SocketAddress bindAddress = new UnixSocketAddress(new File(_unixSocket));
            serverChannel.socket().bind(bindAddress, getAcceptQueueSize());
            serverChannel.configureBlocking(getAcceptors()>0);
            addBean(serverChannel);

            LOG.debug("opened {}",serverChannel);
            _acceptChannel = serverChannel;
        }
    }

    @Override
    public Future<Void> shutdown()
    {
        // shutdown all the connections
        return super.shutdown();
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
                    LOG.warn(e);
                }
            }

            new File(_unixSocket).delete();
        }
    }

    @Override
    public void accept(int acceptorID) throws IOException
    {
        LOG.warn("Blocking UnixSocket accept used.  Cannot be interrupted!");
        UnixServerSocketChannel serverChannel = _acceptChannel;
        if (serverChannel != null && serverChannel.isOpen())
        {
            LOG.debug("accept {}",serverChannel);
            UnixSocketChannel channel = serverChannel.accept();
            LOG.debug("accepted {}",channel);
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

    protected UnixSocketEndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key) throws IOException
    {
        return new UnixSocketEndPoint((UnixSocketChannel)channel,selector,key,getScheduler());
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


    @Override
    public String toString()
    {
        return String.format("%s{%s}",
                super.toString(),
                _unixSocket);
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
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            UnixSocketEndPoint endp = UnixSocketConnector.this.newEndPoint(channel, selector, selectionKey);
            endp.setIdleTimeout(getIdleTimeout());
            return endp;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
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
            LOG.debug("doAccept async {}",server);
            UnixSocketChannel channel = ((UnixServerSocketChannel)server).accept();
            LOG.debug("accepted async {}",channel);
            return channel;
        }
    }
}
