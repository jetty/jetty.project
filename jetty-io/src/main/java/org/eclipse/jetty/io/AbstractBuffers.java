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

import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;

public abstract class AbstractBuffers implements Buffers
{
    protected final Buffers.Type _headerType;
    protected final int _headerSize;
    protected final Buffers.Type _bufferType;
    protected final int _bufferSize;
    protected final Buffers.Type _otherType;

    /* ------------------------------------------------------------ */
    public AbstractBuffers(Buffers.Type headerType, int headerSize, Buffers.Type bufferType, int bufferSize, Buffers.Type otherType)
    {
        _headerType=headerType;
        _headerSize=headerSize;
        _bufferType=bufferType;
        _bufferSize=bufferSize;
        _otherType=otherType;
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
     * Create a new header Buffer
     * @return new Buffer
     */
    final protected Buffer newHeader()
    {
        switch(_headerType)
        {
            case BYTE_ARRAY:
                return new ByteArrayBuffer(_headerSize);
            case DIRECT:
                return new DirectNIOBuffer(_headerSize);
            case INDIRECT:
                return new IndirectNIOBuffer(_headerSize);
        }
        throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new content Buffer
     * @return new Buffer
     */
    final protected Buffer newBuffer()
    {
       switch(_bufferType)
       {
           case BYTE_ARRAY:
               return new ByteArrayBuffer(_bufferSize);
           case DIRECT:
               return new DirectNIOBuffer(_bufferSize);
           case INDIRECT:
               return new IndirectNIOBuffer(_bufferSize);
       }
       throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * Create a new content Buffer
     * @param size
     * @return new Buffer
     */
    final protected Buffer newBuffer(int size)
    {
       switch(_otherType)
       {
           case BYTE_ARRAY:
               return new ByteArrayBuffer(size);
           case DIRECT:
               return new DirectNIOBuffer(size);
           case INDIRECT:
               return new IndirectNIOBuffer(size);
       }
       throw new IllegalStateException();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type to be a Header buffer
     */
    public final boolean isHeader(Buffer buffer)
    {
        if (buffer.capacity()==_headerSize)
        {
            switch(_headerType)
            {
                case BYTE_ARRAY:
                    return buffer instanceof ByteArrayBuffer && !(buffer instanceof  IndirectNIOBuffer);
                case DIRECT:
                    return buffer instanceof  DirectNIOBuffer;
                case INDIRECT:
                    return buffer instanceof  IndirectNIOBuffer;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param buffer
     * @return True if the buffer is the correct type to be a Header buffer
     */
    public final boolean isBuffer(Buffer buffer)
    {
        if (buffer.capacity()==_bufferSize)
        {
            switch(_bufferType)
            {
                case BYTE_ARRAY:
                    return buffer instanceof ByteArrayBuffer && !(buffer instanceof  IndirectNIOBuffer);
                case DIRECT:
                    return buffer instanceof  DirectNIOBuffer;
                case INDIRECT:
                    return buffer instanceof  IndirectNIOBuffer;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("%s [%d,%d]", getClass().getSimpleName(), _headerSize, _bufferSize);
    }
}
