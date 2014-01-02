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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class PooledBuffers extends AbstractBuffers
{
    private final Queue<Buffer> _headers;
    private final Queue<Buffer> _buffers;
    private final Queue<Buffer> _others;
    private final AtomicInteger _size = new AtomicInteger();
    private final int _maxSize;
    private final boolean _otherHeaders;
    private final boolean _otherBuffers;

    /* ------------------------------------------------------------ */
    public PooledBuffers(Buffers.Type headerType, int headerSize, Buffers.Type bufferType, int bufferSize, Buffers.Type otherType,int maxSize)
    {
        super(headerType,headerSize,bufferType,bufferSize,otherType);
        _headers=new ConcurrentLinkedQueue<Buffer>();
        _buffers=new ConcurrentLinkedQueue<Buffer>();
        _others=new ConcurrentLinkedQueue<Buffer>();
        _otherHeaders=headerType==otherType;
        _otherBuffers=bufferType==otherType;
        _maxSize=maxSize;
    }

    /* ------------------------------------------------------------ */
    public Buffer getHeader()
    {
        Buffer buffer = _headers.poll();
        if (buffer==null)
            buffer=newHeader();
        else
            _size.decrementAndGet();
        return buffer;
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer()
    {
        Buffer buffer = _buffers.poll();
        if (buffer==null)
            buffer=newBuffer();
        else
            _size.decrementAndGet();
        return buffer;
    }

    /* ------------------------------------------------------------ */
    public Buffer getBuffer(int size)
    {
        if (_otherHeaders && size==getHeaderSize())
            return getHeader();
        if (_otherBuffers && size==getBufferSize())
            return getBuffer();

        // Look for an other buffer
        Buffer buffer = _others.poll();

        // consume all other buffers until one of the right size is found
        while (buffer!=null && buffer.capacity()!=size)
        {
            _size.decrementAndGet();
            buffer = _others.poll();
        }

        if (buffer==null)
            buffer=newBuffer(size);
        else
            _size.decrementAndGet();
        return buffer;
    }

    /* ------------------------------------------------------------ */
    public void returnBuffer(Buffer buffer)
    {
        buffer.clear();
        if (buffer.isVolatile() || buffer.isImmutable())
            return;

        if (_size.incrementAndGet() > _maxSize)
            _size.decrementAndGet();
        else
        {
            if (isHeader(buffer))
                _headers.add(buffer);
            else if (isBuffer(buffer))
                _buffers.add(buffer);
            else
                _others.add(buffer);
        }
    }

    public String toString()
    {
        return String.format("%s [%d/%d@%d,%d/%d@%d,%d/%d@-]",
                getClass().getSimpleName(),
                _headers.size(),_maxSize,_headerSize,
                _buffers.size(),_maxSize,_bufferSize,
                _others.size(),_maxSize);
    }
}
