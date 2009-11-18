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


/* ------------------------------------------------------------ */
/** Abstract Buffer pool.
 * simple unbounded pool of buffers for header, request and response sizes.
 *
 */
public abstract class ThreadLocalBuffers implements Buffers
{
    private int _bufferSize=12*1024;
    private int _headerSize=6*1024;

    /* ------------------------------------------------------------ */
    private final ThreadLocal<ThreadBuffers> _buffers=new ThreadLocal<ThreadBuffers>()
    {
        @Override
        protected ThreadBuffers initialValue()
        {
            return new ThreadBuffers();
        }
    };

    /* ------------------------------------------------------------ */
    public ThreadLocalBuffers()
    {   
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer()
    {
        ThreadBuffers buffers = _buffers.get();
        if (buffers._buffer!=null)
        {
            Buffer b=buffers._buffer;
            buffers._buffer=null;
            return b;
        }

        if (buffers._other!=null && buffers._other.capacity()==_bufferSize)
        {
            Buffer b=buffers._other;
            buffers._other=null;
            return b;
        }

        return newBuffer(_bufferSize);
    }

    /* ------------------------------------------------------------ */
    public Buffer getHeader()
    {
        ThreadBuffers buffers = _buffers.get();
        if (buffers._header!=null)
        {
            Buffer b=buffers._header;
            buffers._header=null;
            return b;
        }

        if (buffers._other!=null && buffers._other.capacity()==_headerSize && isHeader(buffers._other))
        {
            Buffer b=buffers._other;
            buffers._other=null;
            return b;
        }

        return newHeader(_headerSize);
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer(int size)
    {
        ThreadBuffers buffers = _buffers.get();
        if (buffers._other!=null && buffers._other.capacity()==size)
        {
            Buffer b=buffers._other;
            buffers._other=null;
            return b;
        }

        return newBuffer(size);
    }

    /* ------------------------------------------------------------ */
    public void returnBuffer(Buffer buffer)
    {
        buffer.clear();
        if (buffer.isVolatile() || buffer.isImmutable())
            return;

        int size=buffer.capacity();
        
        ThreadBuffers buffers = _buffers.get();
        
        if (buffers._header==null && size==_headerSize && isHeader(buffer))
            buffers._header=buffer;
        else if (size==_bufferSize && buffers._buffer==null)
            buffers._buffer=buffer;
        else
            buffers._other=buffer;
    }



    /* ------------------------------------------------------------ */
    /**
     * @return Returns the buffer size in bytes.
     */
    public int getBufferSize()
    {
        return _bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the header size in bytes.
     */
    public int getHeaderSize()
    {
        return _headerSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new content Buffer
     * @param size
     * @return new Buffer
     */
    protected abstract Buffer newBuffer(int size);

    /* ------------------------------------------------------------ */
    /**
     * Create a new header Buffer
     * @param size
     * @return new Buffer
     */
    protected abstract Buffer newHeader(int size);

    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type to be a Header buffer
     */
    protected abstract boolean isHeader(Buffer buffer);

    /* ------------------------------------------------------------ */
    /**
     * @param size The buffer size in bytes
     */
    public void setBufferSize( int size )
    {
        _bufferSize = size;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param size The header size in bytes
     */
    public void setHeaderSize( int size )
    {
        _headerSize = size;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "{{"+getHeaderSize()+","+getBufferSize()+"}}";
    }
    
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    protected static class ThreadBuffers
    {
        Buffer _buffer;
        Buffer _header;
        Buffer _other;
    }
}
