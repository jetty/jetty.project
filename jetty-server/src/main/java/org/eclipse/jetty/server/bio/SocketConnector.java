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
 
package org.eclipse.jetty.server.bio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.log.Log;


/* ------------------------------------------------------------------------------- */
/**  Socket Connector.
 * This connector implements a traditional blocking IO and threading model.
 * Normal JRE sockets are used and a thread is allocated per connection.
 * Buffers are managed so that large buffers are only allocated to active connections.
 * 
 * This Connector should only be used if NIO is not available.
 * 
 * @org.apache.xbean.XBean element="bioConnector" description="Creates a BIO based socket connector"
 * 
 * 
 */
public class SocketConnector extends AbstractConnector
{
    protected ServerSocket _serverSocket;
    protected final Set<EndPoint> _connections;
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     * 
     */
    public SocketConnector()
    {
        _connections=new HashSet<EndPoint>();
    }

    /* ------------------------------------------------------------ */
    public Object getConnection()
    {
        return _serverSocket;
    }
    
    /* ------------------------------------------------------------ */
    public void open() throws IOException
    {
        // Create a new server socket and set to non blocking mode
        if (_serverSocket==null || _serverSocket.isClosed())
        _serverSocket= newServerSocket(getHost(),getPort(),getAcceptQueueSize());
        _serverSocket.setReuseAddress(getReuseAddress());
    }

    /* ------------------------------------------------------------ */
    protected ServerSocket newServerSocket(String host, int port,int backlog) throws IOException
    {
        ServerSocket ss= host==null?
            new ServerSocket(port,backlog):
            new ServerSocket(port,backlog,InetAddress.getByName(host));
       
        return ss;
    }
    
    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        if (_serverSocket!=null)
            _serverSocket.close();
        _serverSocket=null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void accept(int acceptorID)
    	throws IOException, InterruptedException
    {   
        Socket socket = _serverSocket.accept();
        configure(socket);
        
        ConnectorEndPoint connection=new ConnectorEndPoint(socket);
        connection.dispatch();
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Allows subclass to override Conection if required.
     */
    protected HttpConnection newHttpConnection(EndPoint endpoint) 
    {
        return new HttpConnection(this, endpoint, getServer());
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public void customize(EndPoint endpoint, Request request)
        throws IOException
    {
        ConnectorEndPoint connection = (ConnectorEndPoint)endpoint;
        int lrmit = isLowResources()?_lowResourceMaxIdleTime:_maxIdleTime;
        if (connection._sotimeout!=lrmit)
        {
            connection._sotimeout=lrmit;
            ((Socket)endpoint.getTransport()).setSoTimeout(lrmit);
        }
              
        super.customize(endpoint, request);
    }

    /* ------------------------------------------------------------------------------- */
    public int getLocalPort()
    {
        if (_serverSocket==null || _serverSocket.isClosed())
            return -1;
        return _serverSocket.getLocalPort();
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    protected void doStart() throws Exception
    {
        _connections.clear();
        super.doStart();
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        Set set=null;

        synchronized(_connections)
        {
            set= new HashSet(_connections);
        }
        
        Iterator iter=set.iterator();
        while(iter.hasNext())
        {
            ConnectorEndPoint connection = (ConnectorEndPoint)iter.next();
            connection.close();
        }
    }

    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    protected class ConnectorEndPoint extends SocketEndPoint implements Runnable, ConnectedEndPoint
    {
        boolean _dispatched=false;
        volatile Connection _connection;
        int _sotimeout;
        protected final Socket _socket;
        
        public ConnectorEndPoint(Socket socket) throws IOException
        {
            super(socket);
            _connection = newHttpConnection(this);
            _sotimeout=socket.getSoTimeout();
            _socket=socket;
        }

        public Connection getConnection()
        {
            return _connection;
        }

        public void setConnection(Connection connection)
        {
            if (_connection!=connection)
                connectionUpgraded(_connection,connection);
            _connection=connection;
        }
        
        public void dispatch() throws IOException
        {
            if (getThreadPool()==null || !getThreadPool().dispatch(this))
            {
                Log.warn("dispatch failed for {}",_connection);
                close();
            }
        }
        
        @Override
        public int fill(Buffer buffer) throws IOException
        {
            int l = super.fill(buffer);
            if (l<0)
                close();
            return l;
        } 
        
        @Override
        public void close() throws IOException
        {
            if (_connection instanceof HttpConnection)
                ((HttpConnection)_connection).getRequest().getAsyncContinuation().cancel();
            super.close();
        }

        public void run()
        {
            try
            {
                connectionOpened(_connection);
                synchronized(_connections)
                {
                    _connections.add(this);
                }
                
                while (isStarted() && !isClosed())
                {
                    if (_connection.isIdle())
                    {
                        if (isLowResources())
                        {
                            int lrmit = getLowResourceMaxIdleTime();
                            if (lrmit>=0 && _sotimeout!= lrmit)
                            {
                                _sotimeout=lrmit;
                                _socket.setSoTimeout(_sotimeout);
                            }
                        }
                    }                    
                    try
                    {
                        _connection.handle();
                    }
                    catch (UpgradeConnectionException e)
                    {
                        Log.debug(e.toString());
                        Log.ignore(e);
                        setConnection(e.getConnection());
                        continue;
                    }
                }
            }
            catch (EofException e)
            {
                Log.debug("EOF", e);
                try{close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            catch (HttpException e)
            {
                Log.debug("BAD", e);
                try{close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            catch(Exception e)
            {
                Log.warn("handle failed?",e);
                try{close();}
                catch(IOException e2){Log.ignore(e2);}
            }
            finally
            { 
                connectionClosed(_connection);
                synchronized(_connections)
                {
                    _connections.remove(this);
                }
            }
        }
    }
}
