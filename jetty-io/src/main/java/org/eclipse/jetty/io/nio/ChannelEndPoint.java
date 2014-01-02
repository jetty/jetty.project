//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
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
    public int fill(Buffer buffer) throws IOException
    {
        if (_ishut)
            return -1;
        Buffer buf = buffer.buffer();
        int len=0;
        if (buf instanceof NIOBuffer)
        {
            final NIOBuffer nbuf = (NIOBuffer)buf;
            final ByteBuffer bbuf=nbuf.getByteBuffer();

            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            try
            {
                synchronized(bbuf)
                {
                    try
                    {
                        bbuf.position(buffer.putIndex());
                        len=_channel.read(bbuf);
                    }
                    finally
                    {
                        buffer.setPutIndex(bbuf.position());
                        bbuf.position(0);
                    }
                }

                if (len<0 && isOpen())
                {
                    if (!isInputShutdown())
                        shutdownInput();
                    if (isOutputShutdown())
                        _channel.close();
                }
            }
            catch (IOException x)
            {
                LOG.debug("Exception while filling", x);
                try
                {
                    if (_channel.isOpen())
                        _channel.close();
                }
                catch (Exception xx)
                {
                    LOG.ignore(xx);
                }

                if (len>0)
                    throw x;
                len=-1;
            }
        }
        else
        {
            throw new IOException("Not Implemented");
        }

        return len;
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer)
     */
    public int flush(Buffer buffer) throws IOException
    {
        Buffer buf = buffer.buffer();
        int len=0;
        if (buf instanceof NIOBuffer)
        {
            final NIOBuffer nbuf = (NIOBuffer)buf;
            final ByteBuffer bbuf=nbuf.getByteBuffer().asReadOnlyBuffer();
            try
            {
                bbuf.position(buffer.getIndex());
                bbuf.limit(buffer.putIndex());
                len=_channel.write(bbuf);
            }
            finally
            {
                if (len>0)
                    buffer.skip(len);
            }
        }
        else if (buf instanceof RandomAccessFileBuffer)
        {
            len = ((RandomAccessFileBuffer)buf).writeTo(_channel,buffer.getIndex(),buffer.length());
            if (len>0)
                buffer.skip(len);
        }
        else if (buffer.array()!=null)
        {
            ByteBuffer b = ByteBuffer.wrap(buffer.array(), buffer.getIndex(), buffer.length());
            len=_channel.write(b);
            if (len>0)
                buffer.skip(len);
        }
        else
        {
            throw new IOException("Not Implemented");
        }
        return len;
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        int length=0;

        Buffer buf0 = header==null?null:header.buffer();
        Buffer buf1 = buffer==null?null:buffer.buffer();

        if (_channel instanceof GatheringByteChannel &&
            header!=null && header.length()!=0 && buf0 instanceof NIOBuffer &&
            buffer!=null && buffer.length()!=0 && buf1 instanceof NIOBuffer)
        {
            length = gatheringFlush(header,((NIOBuffer)buf0).getByteBuffer(),buffer,((NIOBuffer)buf1).getByteBuffer());
        }
        else
        {
            // flush header
            if (header!=null && header.length()>0)
                length=flush(header);

            // flush buffer
            if ((header==null || header.length()==0) &&
                 buffer!=null && buffer.length()>0)
                length+=flush(buffer);

            // flush trailer
            if ((header==null || header.length()==0) &&
                (buffer==null || buffer.length()==0) &&
                 trailer!=null && trailer.length()>0)
                length+=flush(trailer);
        }

        return length;
    }

    protected int gatheringFlush(Buffer header, ByteBuffer bbuf0, Buffer buffer, ByteBuffer bbuf1) throws IOException
    {
        int length;

        synchronized(this)
        {
            // Adjust position indexs of buf0 and buf1
            bbuf0=bbuf0.asReadOnlyBuffer();
            bbuf0.position(header.getIndex());
            bbuf0.limit(header.putIndex());
            bbuf1=bbuf1.asReadOnlyBuffer();
            bbuf1.position(buffer.getIndex());
            bbuf1.limit(buffer.putIndex());

            _gather2[0]=bbuf0;
            _gather2[1]=bbuf1;

            // do the gathering write.
            length=(int)((GatheringByteChannel)_channel).write(_gather2);

            int hl=header.length();
            if (length>hl)
            {
                header.clear();
                buffer.skip(length-hl);
            }
            else if (length>0)
            {
                header.skip(length);
            }
        }
        return length;
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
