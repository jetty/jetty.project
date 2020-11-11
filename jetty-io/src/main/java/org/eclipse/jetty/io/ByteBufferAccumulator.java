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
    private final List<ByteBuffer> _buffers = new ArrayList<>();
    private final ByteBufferPool _bufferPool;

    public ByteBufferAccumulator()
    {
        this(null);
    }

    public ByteBufferAccumulator(ByteBufferPool bufferPool)
    {
        _bufferPool = (bufferPool == null) ? new NullByteBufferPool() : bufferPool;
    }

    public int getLength()
    {
        int length = 0;
        for (ByteBuffer buffer : _buffers)
            length += buffer.remaining();
        return length;
    }

    public ByteBuffer ensureBuffer(int minSize, int minAllocationSize)
    {
        ByteBuffer buffer = _buffers.isEmpty() ? BufferUtil.EMPTY_BUFFER : _buffers.get(_buffers.size() - 1);
        if (BufferUtil.space(buffer) <= minSize)
        {
            buffer = _bufferPool.acquire(minAllocationSize, false);
            _buffers.add(buffer);
        }

        return buffer;
    }

    public void copyBytes(byte[] buf, int offset, int length)
    {
        copyBuffer(BufferUtil.toBuffer(buf, offset, length));
    }

    public void copyBuffer(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            ByteBuffer b = ensureBuffer(0, buffer.remaining());
            int pos = BufferUtil.flipToFill(b);
            BufferUtil.put(buffer, b);
            BufferUtil.flipToFlush(b, pos);
        }
    }

    public ByteBuffer takeByteBuffer()
    {
        int length = getLength();
        ByteBuffer combinedBuffer = _bufferPool.acquire(length, false);
        for (ByteBuffer buffer : _buffers)
        {
            combinedBuffer.put(buffer);
        }
        return combinedBuffer;
    }

    public ByteBuffer toByteBuffer()
    {
        if (_buffers.size() == 1)
            return _buffers.get(0);

        ByteBuffer combinedBuffer = takeByteBuffer();
        _buffers.forEach(_bufferPool::release);
        _buffers.clear();
        _buffers.add(combinedBuffer);
        return combinedBuffer;
    }

    public byte[] toByteArray()
    {
        int length = getLength();
        if (length == 0)
            return new byte[0];

        byte[] bytes = new byte[length];
        writeTo(BufferUtil.toBuffer(bytes));
        return bytes;
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
        _buffers.forEach(_bufferPool::release);
        _buffers.clear();
    }
}
