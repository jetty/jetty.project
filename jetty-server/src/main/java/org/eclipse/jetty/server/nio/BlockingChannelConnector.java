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
import java.nio.channels.ByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.nio.ChannelEndPoint;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------------------------- */
/**  Blocking NIO connector.
 * This connector uses efficient NIO buffers with a traditional blocking thread model.
 * Direct NIO buffers are used and a thread is allocated per connections.
 * 
 * This connector is best used when there are a few very active connections.
 * 
 * @org.apache.xbean.XBean element="blockingNioConnector" description="Creates a blocking NIO based socket connector"
 * 
 * 
 *
 */
public class BlockingChannelConnector extends AbstractNIOConnector 
{
    private transient ServerSocketChannel _acceptChannel;
    private final Set<BlockingChannelEndPoint> _endpoints = Collections.newSetFromMap(new ConcurrentHashMap<BlockingChannelEndPoint,Boolean>());
    
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     * 
     */
    public BlockingChannelConnector()
    {
    }

    /* ------------------------------------------------------------ */
    public Object getConnection()
    {
        return _acceptChannel;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.AbstractConnector#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        getThreadPool().dispatch(new Runnable()
        {

            public void run()
            {
                while (isRunning())
                {
                    try
                    {
                        Thread.sleep(400);
                        long now=System.currentTimeMillis();
                        for (BlockingChannelEndPoint endp : _endpoints)
                        {
                            endp.checkIdleTimestamp(now);
                        }
                    }
                    catch(InterruptedException e)
                    {
                        Log.ignore(e);
                    }
                    catch(Exception e)
                    {
                        Log.warn(e);
                    }
                }
            }
            
        });
        
    }

    
    /* ------------------------------------------------------------ */
    public void open() throws IOException
    {
        // Create a new server socket and set to non blocking mode
        _acceptChannel= ServerSocketChannel.open();
        _acceptChannel.configureBlocking(true);

        // Bind the server socket to the local host and port
        InetSocketAddress addr = getHost()==null?new InetSocketAddress(getPort()):new InetSocketAddress(getHost(),getPort());
        _acceptChannel.socket().bind(addr,getAcceptQueueSize());
    }

    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        if (_acceptChannel != null)
            _acceptChannel.close();
        _acceptChannel=null;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void accept(int acceptorID)
    	throws IOException, InterruptedException
    {   
        SocketChannel channel = _acceptChannel.accept();
        channel.configureBlocking(true);
        Socket socket=channel.socket();
        configure(socket);

        BlockingChannelEndPoint connection=new BlockingChannelEndPoint(channel);
        connection.dispatch();
    }
    
    /* ------------------------------------------------------------------------------- */
    @Override
    public void customize(EndPoint endpoint, Request request)
        throws IOException
    {
        super.customize(endpoint, request);
        endpoint.setMaxIdleTime(_maxIdleTime);
        configure(((SocketChannel)endpoint.getTransport()).socket());
    }


    /* ------------------------------------------------------------------------------- */
    public int getLocalPort()
    {
        if (_acceptChannel==null || !_acceptChannel.isOpen())
            return -1;
        return _acceptChannel.socket().getLocalPort();
    }
    
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    private class BlockingChannelEndPoint extends ChannelEndPoint implements Runnable, ConnectedEndPoint
    {
        private Connection _connection;
        private int _timeout;
        private volatile long _idleTimestamp;
        
        BlockingChannelEndPoint(ByteChannel channel) 
            throws IOException
        {
            super(channel,BlockingChannelConnector.this._maxIdleTime);
            _connection = new HttpConnection(BlockingChannelConnector.this,this,getServer());
        }
        
        /* ------------------------------------------------------------ */
        /** Get the connection.
         * @return the connection
         */
        public Connection getConnection()
        {
            return _connection;
        }
        
        /* ------------------------------------------------------------ */
        public void setConnection(Connection connection)
        {
            _connection=connection;
        }

        /* ------------------------------------------------------------ */
        public void checkIdleTimestamp(long now)
        {
            if (_idleTimestamp!=0 && _timeout>0 && now>(_idleTimestamp+_timeout))
            {
                System.err.println("IDLE "+this);
                idleExpired();
            }
        }

        /* ------------------------------------------------------------ */
        protected void idleExpired()
        {
            try
            {
                close();
            }
            catch (IOException e)
            {
                Log.ignore(e);
            }
        }
        
        /* ------------------------------------------------------------ */
        void dispatch() throws IOException
        {
            if (!getThreadPool().dispatch(this))
            {
                Log.warn("dispatch failed for  {}",_connection);
                BlockingChannelEndPoint.this.close();
            }
        }
        
        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.io.nio.ChannelEndPoint#fill(org.eclipse.jetty.io.Buffer)
         */
        @Override
        public int fill(Buffer buffer) throws IOException
        {
            _idleTimestamp=System.currentTimeMillis();
            return super.fill(buffer);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.io.nio.ChannelEndPoint#flush(org.eclipse.jetty.io.Buffer)
         */
        @Override
        public int flush(Buffer buffer) throws IOException
        {
            _idleTimestamp=System.currentTimeMillis();
            return super.flush(buffer);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.io.nio.ChannelEndPoint#flush(org.eclipse.jetty.io.Buffer, org.eclipse.jetty.io.Buffer, org.eclipse.jetty.io.Buffer)
         */
        @Override
        public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
        {
            _idleTimestamp=System.currentTimeMillis();
            return super.flush(header,buffer,trailer);
        }

        /* ------------------------------------------------------------ */
        public void run()
        {
            try
            {
                _timeout=getMaxIdleTime();
                connectionOpened(_connection);
                _endpoints.add(this);

                while (isOpen())
                {
                    _idleTimestamp=System.currentTimeMillis();
                    if (_connection.isIdle())
                    {
                        if (getServer().getThreadPool().isLowOnThreads())
                        {
                            int lrmit = getLowResourcesMaxIdleTime();
                            if (lrmit>=0 && _timeout!= lrmit)
                            {
                                _timeout=lrmit;
                            }
                        }
                    }
                    else
                    {
                        if (_timeout!=getMaxIdleTime())
                        {
                            _timeout=getMaxIdleTime();
                        }
                    }
                    
                    _connection = _connection.handle();
                }
            }
            catch (EofException e)
            {
                Log.debug("EOF", e);
                try{BlockingChannelEndPoint.this.close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            catch (HttpException e)
            {
                Log.debug("BAD", e);
                try{BlockingChannelEndPoint.this.close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            catch(Throwable e)
            {
                Log.warn("handle failed",e);
                try{BlockingChannelEndPoint.this.close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            finally
            {
                connectionClosed(_connection);
                _endpoints.remove(this);
            }
        }
    }
}
