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



/* ------------------------------------------------------------ */
/** ByteArrayEndPoint.
 * 
 *
 */
public class ByteArrayEndPoint implements ConnectedEndPoint
{
    byte[] _inBytes;
    ByteArrayBuffer _in;
    ByteArrayBuffer _out;
    boolean _closed;
    boolean _nonBlocking;
    boolean _growOutput;
    Connection _connection;

    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public ByteArrayEndPoint()
    {
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
     * @return the nonBlocking
     */
    public boolean isNonBlocking()
    {
        return _nonBlocking;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param nonBlocking the nonBlocking to set
     */
    public void setNonBlocking(boolean nonBlocking)
    {
        _nonBlocking=nonBlocking;
    }

    /* ------------------------------------------------------------ */
    /**
     * 
     */
    public ByteArrayEndPoint(byte[] input, int outputSize)
    {
        _inBytes=input;
        _in=new ByteArrayBuffer(input);
        _out=new ByteArrayBuffer(outputSize);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the in.
     */
    public ByteArrayBuffer getIn()
    {
        return _in;
    }
    /* ------------------------------------------------------------ */
    /**
     * @param in The in to set.
     */
    public void setIn(ByteArrayBuffer in)
    {
        _in = in;
    }
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the out.
     */
    public ByteArrayBuffer getOut()
    {
        return _out;
    }
    /* ------------------------------------------------------------ */
    /**
     * @param out The out to set.
     */
    public void setOut(ByteArrayBuffer out)
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
     * @see org.eclipse.io.EndPoint#isBlocking()
     */
    public boolean isBlocking()
    {
        return !_nonBlocking;
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
    public int fill(Buffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        if (_in==null)
            return -1;
        if (_in.length()<=0)
            return _nonBlocking?0:-1;
        int len = buffer.put(_in);
        _in.skip(len);
        return len;
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer)
     */
    public int flush(Buffer buffer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        if (_growOutput && buffer.length()>_out.space())
        {
            _out.compact();

            if (buffer.length()>_out.space())
            {
                ByteArrayBuffer n = new ByteArrayBuffer(_out.putIndex()+buffer.length());

                n.put(_out.peek(0,_out.putIndex()));
                if (_out.getIndex()>0)
                {
                    n.mark();
                    n.setGetIndex(_out.getIndex());
                }
                _out=n;
            }
        }
        int len = _out.put(buffer);
        buffer.skip(len);
        return len;
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        if (_closed)
            throw new IOException("CLOSED");
        
        int flushed=0;
        
        if (header!=null && header.length()>0)
            flushed=flush(header);
        
        if (header==null || header.length()==0)
        {
            if (buffer!=null && buffer.length()>0)
                flushed+=flush(buffer);
            
            if (buffer==null || buffer.length()==0)
            {
                if (trailer!=null && trailer.length()>0)
                {
                    flushed+=flush(trailer);
                }
            }
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
        _in.clear();
        _out.clear();
        if (_inBytes!=null)
            _in.setPutIndex(_inBytes.length);
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
