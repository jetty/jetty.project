//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;

/**
 * Accumulates data into a list of ByteBuffers which can then be combined into a single buffer or written to an OutputStream.
 * The buffer list automatically grows as data is written to it, the buffers are taken from the
 * supplied {@link ByteBufferPool} or freshly allocated if one is not supplied.
 *
 * The method {@link #ensureBuffer(int, int)} is used to write directly to the last buffer stored in the buffer list,
 * if there is less than a certain amount of space available in that buffer then a new one will be allocated and returned instead.
 * @see #ensureBuffer(int, int)
 */
// TODO: rename to *Aggregator to avoid confusion with RBBP.Accumulator?
public class ByteBufferAccumulator implements AutoCloseable
{
    private final List<RetainableByteBuffer> _buffers = new ArrayList<>();
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;

    public ByteBufferAccumulator()
    {
        this(null, false);
    }

    public ByteBufferAccumulator(ByteBufferPool bufferPool, boolean direct)
    {
        _bufferPool = (bufferPool == null) ? new ByteBufferPool.NonPooling() : bufferPool;
        _direct = direct;
    }

    /**
     * Get the amount of bytes which have been accumulated.
     * This will add up the remaining of each buffer in the accumulator.
     * @return the total length of the content in the accumulator.
     */
    public int getLength()
    {
        int length = 0;
        for (RetainableByteBuffer buffer : _buffers)
            length = Math.addExact(length, buffer.remaining());
        return length;
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     */
    public RetainableByteBuffer ensureBuffer(int minAllocationSize)
    {
        return ensureBuffer(1, minAllocationSize);
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minSize the smallest amount of remaining space before a new buffer is allocated.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     */
    public RetainableByteBuffer ensureBuffer(int minSize, int minAllocationSize)
    {
        RetainableByteBuffer buffer = _buffers.isEmpty() ? null : _buffers.get(_buffers.size() - 1);
        if (buffer == null || BufferUtil.space(buffer.getByteBuffer()) < minSize)
        {
            buffer = _bufferPool.acquire(minAllocationSize, _direct);
            _buffers.add(buffer);
        }
        return buffer;
    }

    public void copyBytes(byte[] buf, int offset, int length)
    {
        copyBuffer(BufferUtil.toBuffer(buf, offset, length));
    }

    public void copyBuffer(ByteBuffer source)
    {
        while (source.hasRemaining())
        {
            RetainableByteBuffer buffer = ensureBuffer(source.remaining());
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int pos = BufferUtil.flipToFill(byteBuffer);
            BufferUtil.put(source, byteBuffer);
            BufferUtil.flipToFlush(byteBuffer, pos);
        }
    }

    /**
     * Take the combined buffer containing all content written to the accumulator.
     * The caller is responsible for releasing this {@link RetainableByteBuffer}.
     * @return a buffer containing all content written to the accumulator.
     * @see #toRetainableByteBuffer()
     */
    public RetainableByteBuffer takeRetainableByteBuffer()
    {
        RetainableByteBuffer combinedBuffer;
        if (_buffers.size() == 1)
        {
            combinedBuffer = _buffers.get(0);
            _buffers.clear();
            return combinedBuffer;
        }

        int length = getLength();
        combinedBuffer = _bufferPool.acquire(length, _direct);
        ByteBuffer byteBuffer = combinedBuffer.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        for (RetainableByteBuffer buffer : _buffers)
        {
            byteBuffer.put(buffer.getByteBuffer());
            buffer.release();
        }
        BufferUtil.flipToFlush(byteBuffer, 0);
        _buffers.clear();
        return combinedBuffer;
    }

    public ByteBuffer takeByteBuffer()
    {
        byte[] bytes = toByteArray();
        close();
        return ByteBuffer.wrap(bytes);
    }

    /**
     * Take the combined buffer containing all content written to the accumulator.
     * The returned buffer is still contained within the accumulator and will be released
     * when the accumulator is closed.
     * @return a buffer containing all content written to the accumulator.
     * @see #takeRetainableByteBuffer()
     * @see #close()
     */
    public RetainableByteBuffer toRetainableByteBuffer()
    {
        RetainableByteBuffer combinedBuffer = takeRetainableByteBuffer();
        _buffers.add(combinedBuffer);
        return combinedBuffer;
    }

    /**
     * @return a newly allocated byte array containing all content written into the accumulator.
     */
    public byte[] toByteArray()
    {
        int length = getLength();
        if (length == 0)
            return new byte[0];

        byte[] bytes = new byte[length];
        ByteBuffer buffer = BufferUtil.toBuffer(bytes);
        BufferUtil.clear(buffer);
        writeTo(buffer);
        return bytes;
    }

    public void writeTo(ByteBuffer byteBuffer)
    {
        int pos = BufferUtil.flipToFill(byteBuffer);
        for (RetainableByteBuffer buffer : _buffers)
        {
            byteBuffer.put(buffer.getByteBuffer().slice());
        }
        BufferUtil.flipToFlush(byteBuffer, pos);
    }

    public void writeTo(OutputStream out) throws IOException
    {
        for (RetainableByteBuffer buffer : _buffers)
        {
            BufferUtil.writeTo(buffer.getByteBuffer().slice(), out);
        }
    }

    @Override
    public void close()
    {
        _buffers.forEach(RetainableByteBuffer::release);
        _buffers.clear();
    }
}
