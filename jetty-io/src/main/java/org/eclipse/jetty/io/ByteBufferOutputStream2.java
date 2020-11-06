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
        releaseAggregateBuffer();
        _accumulator.copyBytes(b, off, len);
    }

    public void write(ByteBuffer buffer)
    {
        releaseAggregateBuffer();
        _accumulator.copyBuffer(buffer);
    }

    public void writeTo(ByteBuffer buffer)
    {
        _accumulator.writeTo(buffer);
    }

    public void writeTo(OutputStream out) throws IOException
    {
        _accumulator.writeTo(out);
    }

    private void releaseAggregateBuffer()
    {
        if (_combinedByteBuffer != null)
        {
            _bufferPool.release(_combinedByteBuffer);
            _combinedByteBuffer = null;
        }
    }

    @Override
    public void close()
    {
        releaseAggregateBuffer();
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
