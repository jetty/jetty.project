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

package org.eclipse.jetty.io;



/* ------------------------------------------------------------ */
/** Abstract Buffer pool.
 * simple unbounded pool of buffers for header, request and response sizes.
 *
 */
public class ThreadLocalBuffers extends AbstractBuffers 
{
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
    public ThreadLocalBuffers(Buffers.Type headerType, int headerSize, Buffers.Type bufferType, int bufferSize, Buffers.Type otherType)
    {
        super(headerType,headerSize,bufferType,bufferSize,otherType);
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

        if (buffers._other!=null && isBuffer(buffers._other))
        {
            Buffer b=buffers._other;
            buffers._other=null;
            return b;
        }

        return newBuffer();
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

        if (buffers._other!=null && isHeader(buffers._other))
        {
            Buffer b=buffers._other;
            buffers._other=null;
            return b;
        }

        return newHeader();
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
        
        ThreadBuffers buffers = _buffers.get();
        
        if (buffers._header==null && isHeader(buffer))
            buffers._header=buffer;
        else if (buffers._buffer==null && isBuffer(buffer))
            buffers._buffer=buffer;
        else
            buffers._other=buffer;
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
