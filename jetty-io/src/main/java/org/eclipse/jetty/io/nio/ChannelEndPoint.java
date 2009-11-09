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
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;


/**
 * 
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class ChannelEndPoint implements EndPoint
{
    protected final ByteChannel _channel;
    protected final ByteBuffer[] _gather2=new ByteBuffer[2];
    protected final Socket _socket;
    protected InetSocketAddress _local;
    protected InetSocketAddress _remote;
    
    /**
     * 
     */
    public ChannelEndPoint(ByteChannel channel)
    {
        super();
        this._channel = channel;
        _socket=(channel instanceof SocketChannel)?((SocketChannel)channel).socket():null;
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

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    public void close() throws IOException
    {
        if (_channel.isOpen())
        {
            try
            {
                if (_channel instanceof SocketChannel)
                {
                    // TODO - is this really required?
                    Socket socket= ((SocketChannel)_channel).socket();
                    if (!socket.isClosed()&&!socket.isOutputShutdown())
                        socket.shutdownOutput();
                }
            }
            catch(IOException e)
            {
                Log.ignore(e);
            }
            catch(UnsupportedOperationException e)
            {
                Log.ignore(e);
            }
            finally
            {
                _channel.close();
            }
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#fill(org.eclipse.io.Buffer)
     */
    public int fill(Buffer buffer) throws IOException
    {
        Buffer buf = buffer.buffer();
        int len=0;
        if (buf instanceof NIOBuffer)
        {
            final NIOBuffer nbuf = (NIOBuffer)buf;
            final ByteBuffer bbuf=nbuf.getByteBuffer();
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized(bbuf)
            {
                try
                {
                    bbuf.position(buffer.putIndex());
                    len=_channel.read(bbuf);
                    if (len<0)
                        _channel.close();
                }
                finally
                {
                    buffer.setPutIndex(bbuf.position());
                    bbuf.position(0);
                }
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
            final ByteBuffer bbuf=nbuf.getByteBuffer();

            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized(bbuf)
            {
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
                    bbuf.position(0);
                    bbuf.limit(bbuf.capacity());
                }
            }
        }
        else if (buf instanceof RandomAccessFileBuffer)
        {
            len = buffer.length();
            ((RandomAccessFileBuffer)buf).writeTo(_channel,buffer.getIndex(),buffer.length());
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
            final NIOBuffer nbuf0 = (NIOBuffer)buf0;
            final ByteBuffer bbuf0=nbuf0.getByteBuffer();
            final NIOBuffer nbuf1 = (NIOBuffer)buf1;
            final ByteBuffer bbuf1=nbuf1.getByteBuffer();

            synchronized(this)
            {
                // We must sync because buffers may be shared (eg nbuf1 is likely to be cached content).
                //noinspection SynchronizationOnLocalVariableOrMethodParameter
                synchronized(bbuf0)
                {
                    //noinspection SynchronizationOnLocalVariableOrMethodParameter
                    synchronized(bbuf1)
                    {
                        try
                        {
                            // Adjust position indexs of buf0 and buf1
                            bbuf0.position(header.getIndex());
                            bbuf0.limit(header.putIndex());
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
                        finally
                        {
                            // adjust buffer 0 and 1
                            if (!header.isImmutable())
                                header.setGetIndex(bbuf0.position());
                            if (!buffer.isImmutable())
                                buffer.setGetIndex(bbuf1.position());

                            bbuf0.position(0);
                            bbuf1.position(0);
                            bbuf0.limit(bbuf0.capacity());
                            bbuf1.limit(bbuf1.capacity());
                        }
                    }
                }
            }
        }
        else
        {
            // TODO - consider copying buffers buffer and trailer into header if there is space!
            
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
        
        if (_local==null)
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
        
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
        
        if (_local==null)
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
        
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
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
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
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
        
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
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();

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
        
        if (_remote==null)
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();

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
    public boolean isBufferingInput()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferingOutput()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferred()
    {
        return false;
    }
}
