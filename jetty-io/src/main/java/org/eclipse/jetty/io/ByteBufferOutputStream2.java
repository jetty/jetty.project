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
    private final ByteBufferAccumulator _accumulator;
    private final ByteBufferPool _bufferPool;
    private ByteBuffer _combinedByteBuffer;
    private int _size = 0;

    public ByteBufferOutputStream2()
    {
        this(null);
    }

    public ByteBufferOutputStream2(ByteBufferPool bufferPool)
    {
        _bufferPool = (bufferPool == null) ? new NullByteBufferPool() : bufferPool;
        _accumulator = new ByteBufferAccumulator(bufferPool);
    }

    /**
     * Get an aggregated content written to the OutputStream in a ByteBuffer.
     * @return the content in a ByteBuffer.
     */
    public ByteBuffer toByteBuffer()
    {
        int length = _accumulator.getLength();
        if (length == 0)
            return BufferUtil.EMPTY_BUFFER;

        if (_combinedByteBuffer != null && length == _combinedByteBuffer.remaining())
            return _combinedByteBuffer;

        ByteBuffer buffer = _bufferPool.acquire(_size, false);
        _accumulator.writeTo(buffer);
        if (_combinedByteBuffer != null)
        {
            _bufferPool.release(_combinedByteBuffer);
            _combinedByteBuffer = buffer;
        }

        return buffer;
    }

    /**
     * Get an aggregated content written to the OutputStream in a byte array.
     * @return the content in a byte array.
     */
    public byte[] toByteArray()
    {
        int length = _accumulator.getLength();
        if (length == 0)
            return new byte[0];

        byte[] bytes = new byte[_size];
        ByteBuffer buffer = BufferUtil.toBuffer(bytes);
        _accumulator.writeTo(buffer);
        return bytes;
    }

    public int size()
    {
        return _accumulator.getLength();
    }

    @Override
    public void write(int b)
    {
        write(new byte[]{(byte)b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len)
    {
        write(BufferUtil.toBuffer(b, off, len));
    }

    public void write(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            ByteBuffer lastBuffer = _accumulator.getBuffer(buffer.remaining());
            int pos = BufferUtil.flipToFill(lastBuffer);
            _size += BufferUtil.put(buffer, lastBuffer);
            BufferUtil.flipToFlush(lastBuffer, pos);
        }
    }

    public void writeTo(OutputStream out) throws IOException
    {
        _accumulator.writeTo(out);
    }

    @Override
    public void close()
    {
        if (_combinedByteBuffer != null)
        {
            _bufferPool.release(_combinedByteBuffer);
            _combinedByteBuffer = null;
        }

        _accumulator.close();
        _size = 0;
    }

    @Override
    public synchronized String toString()
    {
        return String.format("%s@%x{size=%d, bufferPool=%s, byteAccumulator=%s}", getClass().getSimpleName(),
            hashCode(), _size, _bufferPool, _accumulator);
    }
}
