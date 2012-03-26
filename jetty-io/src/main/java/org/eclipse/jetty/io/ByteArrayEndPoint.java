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
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;



/* ------------------------------------------------------------ */
/** ByteArrayEndPoint.
 *
 *
 */
public class ByteArrayEndPoint implements EndPoint
{
    protected byte[] _inBytes;
    protected ByteBuffer _in;
    protected ByteBuffer _out;
    protected boolean _closed;
    protected boolean _growOutput;
    protected int _maxIdleTime;

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ByteArrayEndPoint()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ByteArrayEndPoint(byte[] input, int outputSize)
    {
        _inBytes=input;
        _in=ByteBuffer.wrap(input);
        _out=ByteBuffer.allocate(outputSize);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the in.
     */
    public ByteBuffer getIn()
    {
        return _in;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param in The in to set.
     */
    public void setIn(ByteBuffer in)
    {
        _in = in;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public ByteBuffer getOut()
    {
        return _out;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param out The out to set.
     */
    public void setOut(ByteBuffer out)
    {
        _out = out;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#isOpen()
     */
    public boolean isOpen()
    {
        return !_closed;
    }

    /* ------------------------------------------------------------ */
    /*
     *  @see org.eclipse.jetty.io.EndPoint#isInputShutdown()
     */
    public boolean isInputShutdown()
    {
        return _closed;
    }

    /* ------------------------------------------------------------ */
    /*
     *  @see org.eclipse.jetty.io.EndPoint#isOutputShutdown()
     */
    public boolean isOutputShutdown()
    {
        return _closed;
    }

    /* ------------------------------------------------------------ */
    public boolean blockReadable(long millisecs)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public boolean blockWritable(long millisecs)
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#shutdownOutput()
     */
    public void shutdownOutput() throws IOException
    {
        close();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#shutdownInput()
     */
    public void shutdownInput() throws IOException
    {
        close();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#close()
     */
    public void close() throws IOException
    {
        _closed=true;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#fill(org.eclipse.io.Buffer)
     */
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        if (_in!=null)
            return BufferUtil.flipPutFlip(_in,buffer);
        
        return 0;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer)
     */
    public int flush(ByteBuffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        
        if (_growOutput && buffer.remaining()>_out.remaining())
        {
            _out.compact();

            if (buffer.remaining()>_out.remaining())
            {
                ByteBuffer n = ByteBuffer.allocate(_out.capacity()+buffer.remaining()*2);
                n.put(_out);
                _out=n;
            }
        }

        int put=buffer.remaining();
        _out.put(buffer);
        return put;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    public int gather(ByteBuffer... buffers) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");

        int len=0;
        for (ByteBuffer b : buffers)
        {
            if (b.hasRemaining())
            {
                int l=flush(b);
                if (l>0)
                    len+=l;
                else
                    break;
            }
        }
        return len;
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public void reset()
    {
        _closed=false;
        _in.rewind();
        _out.clear();
    }

    /* ------------------------------------------------------------ */
    @Override
    public InetSocketAddress getLocalAddress()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getConnection()
     */
    public Object getTransport()
    {
        return _inBytes;
    }

    /* ------------------------------------------------------------ */
    public void flush() throws IOException
    {
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the growOutput
     */
    public boolean isGrowOutput()
    {
        return _growOutput;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param growOutput the growOutput to set
     */
    public void setGrowOutput(boolean growOutput)
    {
        _growOutput=growOutput;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.EndPoint#getMaxIdleTime()
     */
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.EndPoint#setMaxIdleTime(int)
     */
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        _maxIdleTime=timeMs;
    }


}
