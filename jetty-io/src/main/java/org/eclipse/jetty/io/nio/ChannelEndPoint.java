// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Channel End Point.
 * <p>Holds the channel and socket for an NIO endpoint.
 *
 */
public class ChannelEndPoint implements EndPoint
{
    private static final Logger LOG = Log.getLogger(ChannelEndPoint.class);

    protected final ByteChannel _channel;
    protected final ByteBuffer[] _gather2=new ByteBuffer[2];
    protected final Socket _socket;
    protected final InetSocketAddress _local;
    protected final InetSocketAddress _remote;
    protected volatile int _maxIdleTime;
    private volatile boolean _ishut;
    private volatile boolean _oshut;

    public ChannelEndPoint(ByteChannel channel) throws IOException
    {
        super();
        this._channel = channel;
        _socket=(channel instanceof SocketChannel)?((SocketChannel)channel).socket():null;
        if (_socket!=null)
        {
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
            _maxIdleTime=_socket.getSoTimeout();
        }
        else
        {
            _local=_remote=null;
        }
    }

    protected ChannelEndPoint(ByteChannel channel, int maxIdleTime) throws IOException
    {
        this._channel = channel;
        _maxIdleTime=maxIdleTime;
        _socket=(channel instanceof SocketChannel)?((SocketChannel)channel).socket():null;
        if (_socket!=null)
        {
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
            _socket.setSoTimeout(_maxIdleTime);
        }
        else
        {
            _local=_remote=null;
        }
    }

    public boolean isBlocking()
    {
        return  !(_channel instanceof SelectableChannel) || ((SelectableChannel)_channel).isBlocking();
    }

    public boolean blockReadable(long millisecs) throws IOException
    {
        return true;
    }

    public boolean blockWritable(long millisecs) throws IOException
    {
        return true;
    }

    /*
     * @see org.eclipse.io.EndPoint#isOpen()
     */
    public boolean isOpen()
    {
        return _channel.isOpen();
    }

    /** Shutdown the channel Input.
     * Cannot be overridden. To override, see {@link #shutdownInput()}
     * @throws IOException
     */
    protected final void shutdownChannelInput() throws IOException
    {
        LOG.debug("ishut {}", this);
        _ishut = true;
        if (_channel.isOpen())
        {
            if (_socket != null)
            {
                try
                {
                    if (!_socket.isInputShutdown())
                    {
                        _socket.shutdownInput();
                    }
                }
                catch (SocketException e)
                {
                    LOG.debug(e.toString());
                    LOG.ignore(e);
                }
                finally
                {
                    if (_oshut)
                    {
                        close();
                    }
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    public void shutdownInput() throws IOException
    {
        shutdownChannelInput();
    }

    protected final void shutdownChannelOutput() throws IOException
    {
        LOG.debug("oshut {}",this);
        _oshut = true;
        if (_channel.isOpen())
        {
            if (_socket != null)
            {
                try
                {
                    if (!_socket.isOutputShutdown())
                    {
                        _socket.shutdownOutput();
                    }
                }
                catch (SocketException e)
                {
                    LOG.debug(e.toString());
                    LOG.ignore(e);
                }
                finally
                {
                    if (_ishut)
                    {
                        close();
                    }
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    public void shutdownOutput() throws IOException
    {
        shutdownChannelOutput();
    }

    public boolean isOutputShutdown()
    {
        return _oshut || !_channel.isOpen() || _socket != null && _socket.isOutputShutdown();
    }

    public boolean isInputShutdown()
    {
        return _ishut || !_channel.isOpen() || _socket != null && _socket.isInputShutdown();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    public void close() throws IOException
    {
        LOG.debug("close {}",this);
        _channel.close();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#fill(org.eclipse.io.Buffer)
     */
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (_ishut)
            return -1;

        int pos=buffer.position();
        try
        {
            buffer.position(buffer.limit());
            buffer.limit(buffer.capacity());

            int filled = _channel.read(buffer);

            if (filled==-1)
                shutdownInput();
            
            return filled;
        }
        finally
        {
            buffer.limit(buffer.position());
            buffer.position(pos);
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer)
     */
    public int flush(ByteBuffer buffer) throws IOException
    {
        int len=_channel.write(buffer);
        return len;
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    public int gather(ByteBuffer... buffers) throws IOException
    {
        int len=0;
        if (_channel instanceof GatheringByteChannel)
        {
            len= (int)((GatheringByteChannel)_channel).write(buffers,0,2);
        }
        else
        {
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    int l=_channel.write(b);
                    if (l>0)
                        len+=l;
                    else
                        break;
                }
            }
        }
        return len;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the channel.
     */
    public ByteChannel getChannel()
    {
        return _channel;
    }


    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getLocalAddr()
     */
    public String getLocalAddr()
    {
        if (_socket==null)
            return null;
       if (_local==null || _local.getAddress()==null || _local.getAddress().isAnyLocalAddress())
           return StringUtil.ALL_INTERFACES;
        return _local.getAddress().getHostAddress();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getLocalHost()
     */
    public String getLocalHost()
    {
        if (_socket==null)
            return null;
       if (_local==null || _local.getAddress()==null || _local.getAddress().isAnyLocalAddress())
           return StringUtil.ALL_INTERFACES;
        return _local.getAddress().getCanonicalHostName();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getLocalPort()
     */
    public int getLocalPort()
    {
        if (_socket==null)
            return 0;
        if (_local==null)
            return -1;
        return _local.getPort();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getRemoteAddr()
     */
    public String getRemoteAddr()
    {
        if (_socket==null)
            return null;
        if (_remote==null)
            return null;
        return _remote.getAddress().getHostAddress();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getRemoteHost()
     */
    public String getRemoteHost()
    {
        if (_socket==null)
            return null;
        if (_remote==null)
            return null;
        return _remote.getAddress().getCanonicalHostName();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getRemotePort()
     */
    public int getRemotePort()
    {
        if (_socket==null)
            return 0;
        return _remote==null?-1:_remote.getPort();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getConnection()
     */
    public Object getTransport()
    {
        return _channel;
    }

    /* ------------------------------------------------------------ */
    public void flush()
        throws IOException
    {
    }

    /* ------------------------------------------------------------ */
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.bio.StreamEndPoint#setMaxIdleTime(int)
     */
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        if (_socket!=null && timeMs!=_maxIdleTime)
            _socket.setSoTimeout(timeMs>0?timeMs:0);
        _maxIdleTime=timeMs;
    }
}
