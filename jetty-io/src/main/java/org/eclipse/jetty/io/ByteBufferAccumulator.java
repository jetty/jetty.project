//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;

public class ByteBufferAccumulator implements AutoCloseable
{
    private static final int MIN_SPACE = 3;
    private static final int DEFAULT_BUFFER_SIZE = 1024;

    private final List<ByteBuffer> _buffers = new ArrayList<>();
    private final ByteBufferPool _bufferPool;

    public ByteBufferAccumulator(ByteBufferPool bufferPool)
    {
        this._bufferPool = bufferPool;
    }

    public int getLength()
    {
        int length = 0;
        for (ByteBuffer buffer : _buffers)
            length += buffer.remaining();
        return length;
    }

    public ByteBuffer getBuffer()
    {
        return getBuffer(DEFAULT_BUFFER_SIZE);
    }

    public ByteBuffer getBuffer(int minAllocationSize)
    {
        ByteBuffer buffer = _buffers.isEmpty() ? BufferUtil.EMPTY_BUFFER : _buffers.get(_buffers.size() - 1);
        if (BufferUtil.space(buffer) <= MIN_SPACE)
        {
            buffer = _bufferPool.acquire(minAllocationSize, false);
            _buffers.add(buffer);
        }

        return buffer;
    }

    public void writeTo(ByteBuffer buffer)
    {
        int pos = BufferUtil.flipToFill(buffer);
        for (ByteBuffer bb : _buffers)
        {
            buffer.put(bb);
        }
        BufferUtil.flipToFlush(buffer, pos);
    }

    public void writeTo(OutputStream out) throws IOException
    {
        for (ByteBuffer bb : _buffers)
        {
            BufferUtil.writeTo(bb, out);
        }
    }

    @Override
    public void close()
    {
        for (ByteBuffer buffer : _buffers)
        {
            _bufferPool.release(buffer);
        }
        _buffers.clear();
    }
}
