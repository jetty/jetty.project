//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;

/**
 * This class implements an output stream in which the data is written into a list of ByteBuffer,
 * the buffer list automatically grows as data is written to it, the buffers are taken from the
 * supplied {@link ByteBufferPool} or freshly allocated if one is not supplied.
 *
 * Designed to mimic {@link java.io.ByteArrayOutputStream} but with better memory usage, and less copying.
 */
public class ByteBufferOutputStream2 extends OutputStream
{
    private static final int DEFAULT_BUFFER_SIZE = 1024;

    private final boolean _direct;
    private final ByteBufferPool _bufferPool;
    private final List<ByteBuffer> _buffers = new LinkedList<>();
    private final int _bufferSize;
    private int _size = 0;

    public ByteBufferOutputStream2()
    {
        this(null);
    }

    public ByteBufferOutputStream2(ByteBufferPool bufferPool)
    {
        this(bufferPool, true, DEFAULT_BUFFER_SIZE);
    }

    public ByteBufferOutputStream2(ByteBufferPool bufferPool, boolean direct, int bufferSize)
    {
        _bufferPool = (bufferPool == null) ? new NullByteBufferPool() : bufferPool;
        _direct = direct;
        _bufferSize = bufferSize;
    }

    /**
     * Get an aggregated content written to the OutputStream in a ByteBuffer.
     * @return the content in a ByteBuffer.
     */
    public ByteBuffer toByteBuffer()
    {
        if (_buffers.isEmpty())
            return BufferUtil.EMPTY_BUFFER;

        if (_buffers.size() == 1)
            return _buffers.get(0);

        ByteBuffer buffer = _bufferPool.acquire(_size, _direct);
        BufferUtil.clearToFill(buffer);
        for (ByteBuffer bb : _buffers)
        {
            buffer.put(bb);
            _bufferPool.release(bb);
        }
        BufferUtil.flipToFlush(buffer, 0);

        _buffers.clear();
        _buffers.add(buffer);
        return buffer;
    }

    /**
     * Get an aggregated content written to the OutputStream in a byte array.
     * @return the content in a byte array.
     */
    public byte[] toByteArray()
    {
        if (_buffers.isEmpty())
            return new byte[0];

        byte[] bytes = new byte[_size];
        ByteBuffer buffer = BufferUtil.toBuffer(bytes);
        BufferUtil.clearToFill(buffer);
        for (ByteBuffer bb : _buffers)
        {
            buffer.put(bb);
        }
        return bytes;
    }

    public int size()
    {
        return _size;
    }

    @Override
    public void write(int b)
    {
        write(new byte[]{(byte)b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len)
    {
        Objects.checkFromIndexSize(off, len, b.length);
        write(BufferUtil.toBuffer(b, off, len));
    }

    public void write(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            ByteBuffer lastBuffer = _buffers.isEmpty() ? BufferUtil.EMPTY_BUFFER : _buffers.get(_buffers.size() - 1);
            if (BufferUtil.isFull(lastBuffer))
            {
                lastBuffer = _bufferPool.newByteBuffer(_bufferSize, _direct);
                _buffers.add(lastBuffer);
            }

            int pos = BufferUtil.flipToFill(lastBuffer);
            _size += BufferUtil.put(buffer, lastBuffer);
            BufferUtil.flipToFlush(lastBuffer, pos);
        }
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
        for (ByteBuffer bb : _buffers)
        {
            _bufferPool.release(bb);
        }
        _buffers.clear();
        _size = 0;
    }

    @Override
    public synchronized String toString()
    {
        return String.format("%s@%x{size=%d, numBuffers=%d, bufferSize=%d, direct=%s, bufferPool=%s}", getClass().getSimpleName(),
            hashCode(), _size, _buffers.size(), _bufferSize, _direct, _bufferPool);
    }
}
