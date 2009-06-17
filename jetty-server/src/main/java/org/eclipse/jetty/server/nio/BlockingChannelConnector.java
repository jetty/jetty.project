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

import org.eclipse.jetty.http.HttpException;
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
    public void accept(int acceptorID)
    	throws IOException, InterruptedException
    {   
        SocketChannel channel = _acceptChannel.accept();
        channel.configureBlocking(true);
        Socket socket=channel.socket();
        configure(socket);

        Connection connection=new Connection(channel);
        connection.dispatch();
    }
    
    /* ------------------------------------------------------------------------------- */
    public void customize(EndPoint endpoint, Request request)
        throws IOException
    {
        Connection connection = (Connection)endpoint;
        if (connection._sotimeout!=_maxIdleTime)
        {
            connection._sotimeout=_maxIdleTime;
            ((SocketChannel)endpoint.getTransport()).socket().setSoTimeout(_maxIdleTime);
        }
              
        super.customize(endpoint, request);
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
    private class Connection extends ChannelEndPoint implements Runnable
    {
        final HttpConnection _connection;
        boolean _dispatched=false;
        int _sotimeout;
        
        Connection(ByteChannel channel) 
        {
            super(channel);
            _connection = new HttpConnection(BlockingChannelConnector.this,this,getServer());
        }
        
        void dispatch() throws IOException
        {
            if (!getThreadPool().dispatch(this))
            {
                Log.warn("dispatch failed for  {}",_connection);
                Connection.this.close();
            }
        }
        
        public void run()
        {
            try
            {
                connectionOpened(_connection);
                
                while (isOpen())
                {
                    if (_connection.isIdle())
                    {
                        if (getServer().getThreadPool().isLowOnThreads())
                        {
                            int lrmit = getLowResourceMaxIdleTime();
                            if (lrmit>=0 && _sotimeout!= lrmit)
                            {
                                _sotimeout=lrmit;
                                ((SocketChannel)getTransport()).socket().setSoTimeout(_sotimeout);
                            }
                        }
                    }
                    _connection.handle();
                }
            }
            catch (EofException e)
            {
                Log.debug("EOF", e);
                try{Connection.this.close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            catch (HttpException e)
            {
                Log.debug("BAD", e);
                try{Connection.this.close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            catch(Throwable e)
            {
                Log.warn("handle failed",e);
                try{Connection.this.close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            finally
            {
                connectionClosed(_connection);
            }
        }
    }
}
