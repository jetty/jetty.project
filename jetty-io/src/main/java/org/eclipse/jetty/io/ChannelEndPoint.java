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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Channel End Point.
 * <p>Holds the channel and socket for an NIO endpoint.
 *
 */
public class ChannelEndPoint extends AbstractEndPoint
{
    private static final Logger LOG = Log.getLogger(ChannelEndPoint.class);

    private final ByteChannel _channel;
    private final Socket _socket;
    private volatile boolean _ishut;
    private volatile boolean _oshut;

    public ChannelEndPoint(SocketChannel channel) throws IOException
    {
        super((InetSocketAddress)channel.socket().getLocalSocketAddress(),
              (InetSocketAddress)channel.socket().getRemoteSocketAddress() );

        this._channel = channel;
        _socket=channel.socket();
        setMaxIdleTime(_socket.getSoTimeout());
        _socket.setSoTimeout(0);
    }

    /*
     * @see org.eclipse.io.EndPoint#isOpen()
     */
    @Override
    public boolean isOpen()
    {
        return _channel.isOpen();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    private void shutdownInput() throws IOException
    {
        _ishut=true;
        if (_oshut)
            close();
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
    @Override
    public void shutdownOutput() throws IOException
    {
        shutdownChannelOutput();
    }

    @Override
    public boolean isOutputShutdown()
    {
        return _oshut || !_channel.isOpen() || _socket != null && _socket.isOutputShutdown();
    }

    @Override
    public boolean isInputShutdown()
    {
        return _ishut || !_channel.isOpen() || _socket != null && _socket.isInputShutdown();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    @Override
    public void close() throws IOException
    {
        LOG.debug("close {}",this);
        _channel.close();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#fill(org.eclipse.io.Buffer)
     */
    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (_ishut)
            return -1;

        int pos=BufferUtil.flipToFill(buffer);
        try
        {
            int filled = _channel.read(buffer);

            if (filled==-1)
                shutdownInput();
            
            return filled;
        }
        catch(IOException e)
        {
            LOG.debug(e);
            shutdownInput();
            return -1;
        }
        finally
        {
            BufferUtil.flipToFlush(buffer,pos);
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    @Override
    public int flush(ByteBuffer... buffers) throws IOException
    {
        int len=0;
        if (buffers.length==1)
            len=_channel.write(buffers[0]);
        else if (buffers.length>1 && _channel instanceof GatheringByteChannel)
            len= (int)((GatheringByteChannel)_channel).write(buffers,0,buffers.length);
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
    @Override
    public Object getTransport()
    {
        return _channel;
    }
    
    /* ------------------------------------------------------------ */
    public Socket getSocket()
    {
        return _socket;
    }
    
}
