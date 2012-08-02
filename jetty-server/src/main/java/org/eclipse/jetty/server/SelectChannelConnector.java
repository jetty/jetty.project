// ========================================================================
// Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Selecting NIO connector.
 * <p>
 * This connector uses efficient NIO buffers with a non blocking threading model. Direct NIO buffers
 * are used and threads are only allocated to connections with requests. Synchronization is used to
 * simulate blocking for the servlet API, and any unflushed content at the end of request handling
 * is written asynchronously.
 * </p>
 * <p>
 * This connector is best used when there are a many connections that have idle periods.
 * </p>
 * <p>
 * When used with {@link org.eclipse.jetty.continuation.Continuation}, threadless waits are supported.
 * If a filter or servlet returns after calling {@link Continuation#suspend()} or when a
 * runtime exception is thrown from a call to {@link Continuation#undispatch()}, Jetty will
 * will not send a response to the client. Instead the thread is released and the Continuation is
 * placed on the timer queue. If the Continuation timeout expires, or it's
 * resume method is called, then the request is again allocated a thread and the request is retried.
 * The limitation of this approach is that request content is not available on the retried request,
 * thus if possible it should be read after the continuation or saved as a request attribute or as the
 * associated object of the Continuation instance.
 * </p>
 */
public class SelectChannelConnector extends AbstractNetConnector
{
    private final SelectorManager _manager;
    protected ServerSocketChannel _acceptChannel;
    protected boolean _inheritChannel;
    private int _localPort=-1;
    
    /* ------------------------------------------------------------ */
    public SelectChannelConnector(Server server)
    {
        this(server,null,null,null,null,false,0,0);
    }
    
    /* ------------------------------------------------------------ */
    public SelectChannelConnector(Server server, boolean ssl)
    {
        this(server,null,null,null,null,ssl,0,0);
    }
    
    /* ------------------------------------------------------------ */
    public SelectChannelConnector(Server server, SslContextFactory sslContextFactory)
    {
        this(server,null,null,null,sslContextFactory,sslContextFactory!=null,0,0);
    }


    /* ------------------------------------------------------------ */
    /**
     * @param server The server this connector will be added to. Must not be null.
     * @param executor An executor for this connector or null to use the servers executor 
     * @param scheduler A scheduler for this connector or null to use the servers scheduler
     * @param pool A buffer pool for this connector or null to use a default {@link ByteBufferPool}
     * @param sslContextFactory An SslContextFactory to use or null if no ssl is required or to use default {@link SslContextFactory} 
     * @param ssl If true, then new connections will assumed to be SSL. If false, connections can only become SSL if they upgrade and a SslContextFactory is passed.
     * @param acceptors the number of acceptor threads to use, or 0 for a default value.
     */
   public SelectChannelConnector(
       @Name("server") Server server,
       @Name("executor") Executor executor,
       @Name("scheduler") ScheduledExecutorService scheduler,
       @Name("bufferPool") ByteBufferPool pool, 
       @Name("sslContextFactory") SslContextFactory sslContextFactory, 
       @Name("ssl") boolean ssl, 
       @Name("acceptors") int acceptors,
       @Name("selectors") int selectors)
    {
        super(server,executor,scheduler,pool,sslContextFactory,ssl,acceptors);
        _manager=new ConnectorSelectorManager(selectors!=0?selectors:Math.max(1,(Runtime.getRuntime().availableProcessors())/4));
        addBean(_manager,true);
    }


    public boolean isInheritChannel()
    {
        return _inheritChannel;
    }

    /**
     * If true, the connector first tries to inherit from a channel provided by the system. 
     * If there is no inherited channel available, or if the inherited channel provided not usable, 
     * then it will fall back upon normal ServerSocketChannel creation.
     * <p> 
     * Use it with xinetd/inetd, to launch an instance of Jetty on demand. The port
     * used to access pages on the Jetty instance is the same as the port used to
     * launch Jetty. 
     * 
     */
    public void setInheritChannel(boolean inheritChannel)
    {
        _inheritChannel = inheritChannel;
    }

    @Override
    public void accept(int acceptorID) throws IOException
    {
        ServerSocketChannel server;
        synchronized(this)
        {
            server = _acceptChannel;
        }

        if (server!=null && server.isOpen() && _manager.isStarted())
        {
            SocketChannel channel = server.accept();
            channel.configureBlocking(false);
            Socket socket = channel.socket();
            configure(socket);
            _manager.accept(channel);
        }
    }

    @Override
    public void close() throws IOException
    {
        synchronized(this)
        {
            if (_acceptChannel != null)
            {
                removeBean(_acceptChannel);
                if (_acceptChannel.isOpen())
                    _acceptChannel.close();
            }
            _acceptChannel = null;
            _localPort=-2;
        }
    }

    public SelectorManager getSelectorManager()
    {
        return _manager;
    }

    @Override
    public synchronized Object getTransport()
    {
        return _acceptChannel;
    }

    @Override
    public int getLocalPort()
    {
        synchronized(this)
        {
            return _localPort;
        }
    }

    @Override
    public void open() throws IOException
    {
        synchronized(this)
        {
            if (_acceptChannel == null)
            {
                if (_inheritChannel)
                {
                    Channel channel = System.inheritedChannel();
                    if ( channel instanceof ServerSocketChannel )
                        _acceptChannel = (ServerSocketChannel)channel;
                    else
                        LOG.warn("Unable to use System.inheritedChannel() [" +channel+ "]. Trying a new ServerSocketChannel at " + getHost() + ":" + getPort());
                }

                if (_acceptChannel == null)
                {
                    // Create a new server socket
                    _acceptChannel = ServerSocketChannel.open();

                    // Bind the server socket to the local host and port
                    _acceptChannel.socket().setReuseAddress(getReuseAddress());
                    InetSocketAddress addr = getHost()==null?new InetSocketAddress(getPort()):new InetSocketAddress(getHost(),getPort());
                    _acceptChannel.socket().bind(addr,getAcceptQueueSize());

                    _localPort=_acceptChannel.socket().getLocalPort();
                    if (_localPort<=0)
                        throw new IOException("Server channel not bound");

                    addBean(_acceptChannel);
                }

                _acceptChannel.configureBlocking(true);
                addBean(_acceptChannel);
            }
        }
    }
    
    
    /*
     * @see org.eclipse.jetty.server.server.AbstractConnector#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        return new SelectChannelEndPoint(channel,selectSet,key, getScheduler(), getIdleTimeout());
    }

    protected void endPointClosed(AsyncEndPoint endpoint)
    {
        connectionClosed(endpoint.getAsyncConnection());
    }

    /* ------------------------------------------------------------ */
    private final class ConnectorSelectorManager extends SelectorManager
    {
        private ConnectorSelectorManager(int selectSets)
        {
            super(selectSets);
        }

        @Override
        protected void execute(Runnable task)
        {
            getExecutor().execute(task);
        }

        @Override
        protected void endPointClosed(AsyncEndPoint endpoint)
        {
            SelectChannelConnector.this.connectionClosed(endpoint.getAsyncConnection());
            super.endPointClosed(endpoint);
        }

        @Override
        protected void endPointOpened(AsyncEndPoint endpoint)
        {
            // TODO handle max connections and low resources
            super.endPointOpened(endpoint);
            SelectChannelConnector.this.connectionOpened(endpoint.getAsyncConnection());
        }

        @Override
        public void connectionUpgraded(AsyncEndPoint endpoint, AsyncConnection oldConnection)
        {
            super.connectionUpgraded(endpoint, oldConnection);
            SelectChannelConnector.this.connectionUpgraded(oldConnection, endpoint.getAsyncConnection());
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
        {
            return SelectChannelConnector.this.newEndPoint(channel,selectSet, selectionKey);
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment) throws IOException
        {
            return SelectChannelConnector.this.newConnection(endpoint);
        }
    }
}
