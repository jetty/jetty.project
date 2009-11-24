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

package org.eclipse.jetty.server.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout.Task;

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
 * When used with {@link org.eclipse.jetty.continuation.Continuation}, threadless waits are supported. When
 * a filter or servlet calls getEvent on a Continuation, a {@link org.eclipse.jetty.server.RetryRequest}
 * runtime exception is thrown to allow the thread to exit the current request handling. Jetty will
 * catch this exception and will not send a response to the client. Instead the thread is released
 * and the Continuation is placed on the timer queue. If the Continuation timeout expires, or it's
 * resume method is called, then the request is again allocated a thread and the request is retried.
 * The limitation of this approach is that request content is not available on the retried request,
 * thus if possible it should be read after the continuation or saved as a request attribute or as the
 * associated object of the Continuation instance.
 * </p>
 * 
 * @org.apache.xbean.XBean element="nioConnector" description="Creates an NIO based socket connector"
 * 
 * 
 *
 */
public class SelectChannelConnector extends AbstractNIOConnector 
{
    protected ServerSocketChannel _acceptChannel;
    private int _lowResourcesConnections;
    private int _lowResourcesMaxIdleTime;

    private final SelectorManager _manager = new SelectorManager()
    {
        @Override
        protected SocketChannel acceptChannel(SelectionKey key) throws IOException
        {
            // TODO handle max connections
            SocketChannel channel = ((ServerSocketChannel)key.channel()).accept();
            if (channel==null)
                return null;
            channel.configureBlocking(false);
            Socket socket = channel.socket();
            configure(socket);
            return channel;
        }

        @Override
        public boolean dispatch(Runnable task)
        {
            return getThreadPool().dispatch(task);
        }

        @Override
        protected void endPointClosed(final SelectChannelEndPoint endpoint)
        {
            connectionClosed(endpoint.getConnection());
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
            // TODO handle max connections and low resources
            connectionOpened(endpoint.getConnection());
        }
        
        @Override
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
            connectionUpgraded(oldConnection,endpoint.getConnection());
        }

        @Override
        protected Connection newConnection(SocketChannel channel,SelectChannelEndPoint endpoint)
        {
            return SelectChannelConnector.this.newConnection(channel,endpoint);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey sKey) throws IOException
        {
            return SelectChannelConnector.this.newEndPoint(channel,selectSet,sKey);
        }
    };
    
    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     * 
     */
    public SelectChannelConnector()
    {
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void accept(int acceptorID) throws IOException
    {
        _manager.doSelect(acceptorID);
    }
    
    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        synchronized(this)
        {
            if(_manager.isRunning())
            {
                try
                {
                    _manager.stop();
                }
                catch (Exception e)
                {
                    Log.warn(e);
                }
            }
            if (_acceptChannel != null)
                _acceptChannel.close();
            _acceptChannel = null;
        }
    }
    
    /* ------------------------------------------------------------------------------- */
    @Override
    public void customize(EndPoint endpoint, Request request) throws IOException
    {
        SelectChannelEndPoint cep = ((SelectChannelEndPoint)endpoint);
        cep.cancelIdle();
        request.setTimeStamp(cep.getSelectSet().getNow());
        super.customize(endpoint, request);
    }
    
    /* ------------------------------------------------------------------------------- */
    @Override
    public void persist(EndPoint endpoint) throws IOException
    {
        ((SelectChannelEndPoint)endpoint).scheduleIdle();
        super.persist(endpoint);
    }

    /* ------------------------------------------------------------ */
    public Object getConnection()
    {
        return _acceptChannel;
    }

    /* ------------------------------------------------------------------------------- */
    public int getLocalPort()
    {
        synchronized(this)
        {
            if (_acceptChannel==null || !_acceptChannel.isOpen())
                return -1;
            return _acceptChannel.socket().getLocalPort();
        }
    }

    /* ------------------------------------------------------------ */
    public void open() throws IOException
    {
        synchronized(this)
        {
            if (_acceptChannel == null)
            {
                // Create a new server socket
                _acceptChannel = ServerSocketChannel.open();

                // Bind the server socket to the local host and port
                _acceptChannel.socket().setReuseAddress(getReuseAddress());
                InetSocketAddress addr = getHost()==null?new InetSocketAddress(getPort()):new InetSocketAddress(getHost(),getPort());
                _acceptChannel.socket().bind(addr,getAcceptQueueSize());

                // Set to non blocking mode
                _acceptChannel.configureBlocking(false);
                
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxIdleTime(int maxIdleTime)
    {
        _manager.setMaxIdleTime(maxIdleTime);
        super.setMaxIdleTime(maxIdleTime);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the lowResourcesConnections
     */
    public int getLowResourcesConnections()
    {
        return _lowResourcesConnections;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the number of connections, which if exceeded places this manager in low resources state.
     * This is not an exact measure as the connection count is averaged over the select sets.
     * @param lowResourcesConnections the number of connections
     * @see {@link #setLowResourcesMaxIdleTime(int)}
     */
    public void setLowResourcesConnections(int lowResourcesConnections)
    {
        _lowResourcesConnections=lowResourcesConnections;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the lowResourcesMaxIdleTime
     */
    @Override
    public int getLowResourcesMaxIdleTime()
    {
        return _lowResourcesMaxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the period in ms that a connection is allowed to be idle when this there are more
     * than {@link #getLowResourcesConnections()} connections.  This allows the server to rapidly close idle connections
     * in order to gracefully handle high load situations.
     * @param lowResourcesMaxIdleTime the period in ms that a connection is allowed to be idle when resources are low.
     * @see {@link #setMaxIdleTime(long)}
     */
    @Override
    public void setLowResourcesMaxIdleTime(int lowResourcesMaxIdleTime)
    {
        _lowResourcesMaxIdleTime=lowResourcesMaxIdleTime;
        super.setLowResourcesMaxIdleTime(lowResourcesMaxIdleTime); 
    }

    
    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.AbstractConnector#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        _manager.setSelectSets(getAcceptors());
        _manager.setMaxIdleTime(getMaxIdleTime());
        _manager.setLowResourcesConnections(getLowResourcesConnections());
        _manager.setLowResourcesMaxIdleTime(getLowResourcesMaxIdleTime());
        _manager.start();
        open();
        _manager.register(_acceptChannel);
        super.doStart();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.server.server.AbstractConnector#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {        
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
    {
        return new SelectChannelEndPoint(channel,selectSet,key);
    }

    /* ------------------------------------------------------------------------------- */
    protected Connection newConnection(SocketChannel channel,final SelectChannelEndPoint endpoint)
    {
        return new HttpConnection(SelectChannelConnector.this,endpoint,getServer())
        {
            /* ------------------------------------------------------------ */
            @Override
            public void cancelTimeout(Task task)
            {
                endpoint.getSelectSet().cancelTimeout(task);
            }

            /* ------------------------------------------------------------ */
            @Override
            public void scheduleTimeout(Task task, long timeoutMs)
            {
                endpoint.getSelectSet().scheduleTimeout(task,timeoutMs);
            }
        };
    }

    /* ------------------------------------------------------------------------------- */
    public void dump()
    {
        Log.info("channel "+_acceptChannel+(_acceptChannel.isOpen()?" is open":" is closed"));
        _manager.dump();
    }
}
