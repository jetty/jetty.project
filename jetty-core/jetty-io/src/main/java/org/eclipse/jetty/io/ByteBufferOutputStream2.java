//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
    private int _size = 0;

    public ByteBufferOutputStream2()
    {
        this(null, false);
    }

    public ByteBufferOutputStream2(ByteBufferPool bufferPool, boolean direct)
    {
        _accumulator = new ByteBufferAccumulator(bufferPool == null ? ByteBufferPool.NON_POOLING : bufferPool, direct);
    }

    /**
     * Take the combined buffer containing all content written to the OutputStream.
     * The caller is responsible for releasing this {@link RetainableByteBuffer}.
     * @return a buffer containing all content written to the OutputStream.
     */
    public RetainableByteBuffer takeByteBuffer()
    {
        return _accumulator.takeRetainableByteBuffer();
    }

    /**
     * Take the combined buffer containing all content written to the OutputStream.
     * The returned buffer is still contained within the OutputStream and will be released
     * when the OutputStream is closed.
     * @return a buffer containing all content written to the OutputStream.
     */
    public RetainableByteBuffer toByteBuffer()
    {
        return _accumulator.toRetainableByteBuffer();
    }

    /**
     * @return a newly allocated byte array containing all content written into the OutputStream.
     */
    public byte[] toByteArray()
    {
        return _accumulator.toByteArray();
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
        _size += len;
        _accumulator.copyBytes(b, off, len);
    }

    public void write(ByteBuffer buffer)
    {
        _size += buffer.remaining();
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

    @Override
    public void close()
    {
        _accumulator.close();
        _size = 0;
    }

    @Override
    public synchronized String toString()
    {
        return String.format("%s@%x{size=%d, byteAccumulator=%s}", getClass().getSimpleName(),
            hashCode(), _size, _accumulator);
    }
}
