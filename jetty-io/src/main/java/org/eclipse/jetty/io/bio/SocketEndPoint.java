// ========================================================================
// Copyright (c) 2004-2010 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io.bio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class SocketEndPoint extends StreamEndPoint
{
    private static final Logger LOG = Log.getLogger(SocketEndPoint.class);

    final Socket _socket;
    final InetSocketAddress _local;
    final InetSocketAddress _remote;
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public SocketEndPoint(Socket socket)
    	throws IOException	
    {
        super(socket.getInputStream(),socket.getOutputStream());
        _socket=socket;
        _local=(InetSocketAddress)_socket.getLocalSocketAddress();
        _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
        super.setMaxIdleTime(_socket.getSoTimeout());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * 
     */
    protected SocketEndPoint(Socket socket, int maxIdleTime)
        throws IOException      
    {
        super(socket.getInputStream(),socket.getOutputStream());
        _socket=socket;
        _local=(InetSocketAddress)_socket.getLocalSocketAddress();
        _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
        _socket.setSoTimeout(maxIdleTime>0?maxIdleTime:0);
        super.setMaxIdleTime(maxIdleTime);
    }
    
    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.io.BufferIO#isClosed()
     */
    @Override
    public boolean isOpen()
    {
        return super.isOpen() && _socket!=null && !_socket.isClosed();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean isInputShutdown()
    {
        return !super.isOpen() || _socket!=null && _socket.isInputShutdown();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean isOutputShutdown()
    {
        return !super.isOpen() || _socket!=null && _socket.isOutputShutdown();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.io.bio.StreamEndPoint#shutdownOutput()
     */
    @Override
    public void shutdownOutput() throws IOException
    {    
        if (!_socket.isClosed() && !_socket.isOutputShutdown())
            _socket.shutdownOutput();
    }


    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.jetty.io.bio.StreamEndPoint#shutdownOutput()
     */
    @Override
    public void shutdownInput() throws IOException
    {    
        if (!_socket.isClosed() && !_socket.isInputShutdown())
            _socket.shutdownInput();
    }
    
    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.io.BufferIO#close()
     */
    @Override
    public void close() throws IOException
    {
        _socket.close();
        _in=null;
        _out=null;
    }
    

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getLocalAddr()
     */
    @Override
    public String getLocalAddr()
    {
       if (_local==null || _local.getAddress()==null || _local.getAddress().isAnyLocalAddress())
           return StringUtil.ALL_INTERFACES;
        
        return _local.getAddress().getHostAddress();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getLocalHost()
     */
    @Override
    public String getLocalHost()
    {
       if (_local==null || _local.getAddress()==null || _local.getAddress().isAnyLocalAddress())
           return StringUtil.ALL_INTERFACES;
        
        return _local.getAddress().getCanonicalHostName();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getLocalPort()
     */
    @Override
    public int getLocalPort()
    {
        if (_local==null)
            return -1;
        return _local.getPort();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getRemoteAddr()
     */
    @Override
    public String getRemoteAddr()
    {
        if (_remote==null)
            return null;
        InetAddress addr = _remote.getAddress();
        return ( addr == null ? null : addr.getHostAddress() );
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getRemoteHost()
     */
    @Override
    public String getRemoteHost()
    {
        if (_remote==null)
            return null;
        return _remote.getAddress().getCanonicalHostName();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getRemotePort()
     */
    @Override
    public int getRemotePort()
    {
        if (_remote==null)
            return -1;
        return _remote.getPort();
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#getConnection()
     */
    @Override
    public Object getTransport()
    {
        return _socket;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.bio.StreamEndPoint#setMaxIdleTime(int)
     */
    @Override
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        if (timeMs!=getMaxIdleTime())
            _socket.setSoTimeout(timeMs>0?timeMs:0);
        super.setMaxIdleTime(timeMs);
    }


    /* ------------------------------------------------------------ */
    @Override
    protected void idleExpired() throws IOException
    {
        try
        {
            if (!isInputShutdown())
                shutdownInput();
        }
        catch(IOException e)
        {
            LOG.ignore(e);
            _socket.close();
        }
    }
    
}
