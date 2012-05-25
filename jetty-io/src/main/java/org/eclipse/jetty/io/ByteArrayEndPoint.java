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
import java.nio.charset.Charset;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;


/* ------------------------------------------------------------ */
/** ByteArrayEndPoint.
 *
 */
public class ByteArrayEndPoint extends AbstractEndPoint
{
    public final static InetSocketAddress NOIP=new InetSocketAddress(0);

    protected ByteBuffer _in;
    protected ByteBuffer _out;
    protected boolean _ishut;
    protected boolean _oshut;
    protected boolean _closed;
    protected boolean _growOutput;

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ByteArrayEndPoint()
    {
        super(NOIP,NOIP);
        _in=BufferUtil.EMPTY_BUFFER;
        _out=BufferUtil.allocate(1024);
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ByteArrayEndPoint(byte[] input, int outputSize)
    {
        super(NOIP,NOIP);
        _in=input==null?null:ByteBuffer.wrap(input);
        _out=BufferUtil.allocate(outputSize);
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public ByteArrayEndPoint(String input, int outputSize)
    {
        super(NOIP,NOIP);
        setInput(input);
        _out=BufferUtil.allocate(outputSize);
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
     */
    public void setInputEOF()
    {
        _in = null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param in The in to set.
     */
    public void setInput(ByteBuffer in)
    {
        _in = in;
    }

    /* ------------------------------------------------------------ */
    public void setInput(String s)
    {
        setInput(BufferUtil.toBuffer(s,StringUtil.__UTF8_CHARSET));
    }

    /* ------------------------------------------------------------ */
    public void setInput(String s,Charset charset)
    {
        setInput(BufferUtil.toBuffer(s,charset));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public ByteBuffer getOutput()
    {
        return _out;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public String getOutputString()
    {
        return BufferUtil.toString(_out,StringUtil.__UTF8_CHARSET);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public ByteBuffer takeOutput()
    {
        ByteBuffer b=_out;
        _out=BufferUtil.allocate(b.capacity());
        return b;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public String takeOutputString()
    {
        ByteBuffer buffer=takeOutput();
        return BufferUtil.toString(buffer,StringUtil.__UTF8_CHARSET);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public String getOutputString(Charset charset)
    {
        return BufferUtil.toString(_out,charset);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param out The out to set.
     */
    public void setOutput(ByteBuffer out)
    {
        _out = out;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#isOpen()
     */
    @Override
    public boolean isOpen()
    {
        return !_closed;
    }

    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public boolean isInputShutdown()
    {
        return _ishut||_closed;
    }

    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public boolean isOutputShutdown()
    {
        return _oshut||_closed;
    }

    /* ------------------------------------------------------------ */
    private void shutdownInput() throws IOException
    {
        _ishut=true;
        if (_oshut)
            close();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#shutdownOutput()
     */
    @Override
    public void shutdownOutput()
    {
        _oshut=true;
        if (_ishut)
            close();
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#close()
     */
    @Override
    public void close()
    {
        _closed=true;
        onClose();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return <code>true</code> if there are bytes remaining to be read from the encoded input
     */
    public boolean hasMore()
    {
        return getOutput().position()>0;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#fill(org.eclipse.io.Buffer)
     */
    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        if (_in==null)
            shutdownInput();
        if (_ishut)
            return -1;
        int filled=BufferUtil.append(_in,buffer);
        if (filled>0)
            notIdle();
        return filled;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    @Override
    public int flush(ByteBuffer... buffers) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        if (_oshut)
            throw new IOException("OSHUT");

        int flushed=0;

        for (ByteBuffer b : buffers)
        {
            if (BufferUtil.hasContent(b))
            {
                if (_growOutput && b.remaining()>BufferUtil.space(_out))
                {
                    BufferUtil.compact(_out);
                    if (b.remaining()>BufferUtil.space(_out))
                    {
                        ByteBuffer n = BufferUtil.allocate(_out.capacity()+b.remaining()*2);
                        BufferUtil.append(_out,n);
                        _out=n;
                    }
                }

                flushed+=BufferUtil.append(b,_out);

                if (BufferUtil.hasContent(b))
                    break;
            }
        }
        if (flushed>0)
            notIdle();
        return flushed;
    }

    /* ------------------------------------------------------------ */
    /**
     *
     */
    public void reset()
    {
        _ishut=false;
        _oshut=false;
        _closed=false;
        _in=null;
        BufferUtil.clear(_out);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getConnection()
     */
    @Override
    public Object getTransport()
    {
        return null;
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


}
