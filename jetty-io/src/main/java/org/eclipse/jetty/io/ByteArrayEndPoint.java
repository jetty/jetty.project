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
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;



/* ------------------------------------------------------------ */
/** ByteArrayEndPoint.
 *
 *
 */
public class ByteArrayEndPoint implements ConnectedEndPoint
{
    protected byte[] _inBytes;
    protected ByteBuffer _in;
    protected ByteBuffer _out;
    protected boolean _closed;
    protected boolean _growOutput;
    protected Connection _connection;
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
     * @see org.eclipse.jetty.io.ConnectedEndPoint#getConnection()
     */
    public Connection getConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.ConnectedEndPoint#setConnection(org.eclipse.jetty.io.Connection)
     */
    public void setConnection(Connection connection)
    {
        _connection=connection;
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
            return BufferUtil.put(_in,buffer);
        
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
    public int flush(ByteBuffer header, ByteBuffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");

        int flushed=0;

        if (header!=null && header.remaining()>0)
            flushed=flush(header);

        if (header==null || header.remaining()==0)
        {
            if (buffer!=null && buffer.remaining()>0)
                flushed+=flush(buffer);
        }

        return flushed;
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
    /*
     * @see org.eclipse.io.EndPoint#getLocalAddr()
     */
    public String getLocalAddr()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getLocalHost()
     */
    public String getLocalHost()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getLocalPort()
     */
    public int getLocalPort()
    {
        return 0;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getRemoteAddr()
     */
    public String getRemoteAddr()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getRemoteHost()
     */
    public String getRemoteHost()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.io.EndPoint#getRemotePort()
     */
    public int getRemotePort()
    {
        return 0;
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
