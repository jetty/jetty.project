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
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SelectorManager.ManagedSelector;
import org.eclipse.jetty.server.Connector.NetConnector;

/* ------------------------------------------------------------------------------- */
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
 *
 * @org.apache.xbean.XBean element="nioConnector" description="Creates an NIO based socket connector"
 */
public class ChannelHttpConnector extends HttpConnector implements NetConnector
{
    private final SelectorManager _manager;
    protected ServerSocketChannel _acceptChannel;
    private int _localPort=-1;

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     *
     */
    public ChannelHttpConnector()
    {
        this(Math.max(1,(Runtime.getRuntime().availableProcessors())/4),
             Math.max(1,(Runtime.getRuntime().availableProcessors())/4));
    }
    
    /* ------------------------------------------------------------ */
    public ChannelHttpConnector(int acceptors, int selectors)
    {
        super(acceptors);
        _manager=new ConnectorSelectorManager(selectors);
        addBean(_manager,true);
    }
    

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    @Override
    public void close()
    {
        synchronized(this)
        {
            if (_acceptChannel != null)
            {
                removeBean(_acceptChannel);
                try
                {
                    if (_acceptChannel.isOpen())
                        _acceptChannel.close();
                }
                catch(IOException e)
                {
                    LOG.warn(e);
                }
            }
            _acceptChannel = null;
            _localPort=-2;
        }
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public void customize(Request request) throws IOException
    {
        request.setTimeStamp(System.currentTimeMillis());
        super.customize(request);
    }

    /* ------------------------------------------------------------ */
    public SelectorManager getSelectorManager()
    {
        return _manager;
    }

    /* ------------------------------------------------------------ */
    @Override
    public synchronized Object getTransport()
    {
        return _acceptChannel;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public int getLocalPort()
    {
        synchronized(this)
        {
            return _localPort;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void open() throws IOException
    {
        synchronized(this)
        {
            if (_acceptChannel == null)
            {
                // Create a new server socket
                _acceptChannel = ServerSocketChannel.open();
                // Set to blocking mode
                _acceptChannel.configureBlocking(true);

                // Bind the server socket to the local host and port
                _acceptChannel.socket().setReuseAddress(getReuseAddress());
                InetSocketAddress addr = getHost()==null?new InetSocketAddress(getPort()):new InetSocketAddress(getHost(),getPort());
                _acceptChannel.socket().bind(addr,getAcceptQueueSize());

                _localPort=_acceptChannel.socket().getLocalPort();
                if (_localPort<=0)
                    throw new IOException("Server channel not bound");

                addBean(_acceptChannel);
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxIdleTime(int maxIdleTime)
    {
        super.setMaxIdleTime(maxIdleTime);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.AbstractConnector#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        SelectChannelEndPoint endp= new SelectChannelEndPoint(channel,selectSet,key, ChannelHttpConnector.this._maxIdleTime);
        endp.setAsyncConnection(selectSet.getManager().newConnection(channel,endp, key.attachment()));
        return endp;
    }

    /* ------------------------------------------------------------------------------- */
    protected void endPointClosed(AsyncEndPoint endpoint)
    {
        endpoint.onClose();
        connectionClosed(endpoint.getAsyncConnection());
    }

    /* ------------------------------------------------------------------------------- */
    protected AsyncConnection newConnection(SocketChannel channel,final AsyncEndPoint endpoint)
    {
        return new HttpConnection(ChannelHttpConnector.this,endpoint,getServer());
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
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
            findExecutor().execute(task);
        }

        @Override 
        protected int getMaxIdleTime()
        {
            return ChannelHttpConnector.this.getMaxIdleTime();
        }
        
        @Override
        protected void endPointClosed(AsyncEndPoint endpoint)
        {
            ChannelHttpConnector.this.endPointClosed(endpoint);
        }

        @Override
        protected void endPointOpened(AsyncEndPoint endpoint)
        {
            // TODO handle max connections and low resources
            connectionOpened(endpoint.getAsyncConnection());
        }

        @Override
        protected void endPointUpgraded(AsyncEndPoint endpoint, AsyncConnection oldConnection)
        {
            connectionUpgraded(oldConnection,endpoint.getAsyncConnection());
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            return ChannelHttpConnector.this.newConnection(channel,endpoint);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey sKey) throws IOException
        {
            return ChannelHttpConnector.this.newEndPoint(channel,selectSet,sKey);
        }
    }
}
