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

import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;

/**
 * This class implements an output stream in which the data is buffered.
 * <p>
 * Designed to mimic {@link java.io.ByteArrayOutputStream} but with better memory usage, and less copying.
 * @deprecated Use {@link Content.Sink#asBuffered(Content.Sink, ByteBufferPool, boolean, int, int)}
 */
@Deprecated
public class ByteBufferOutputStream2 extends OutputStream
{
    private final RetainableByteBuffer.DynamicCapacity _accumulator;
    private int _size = 0;

    public ByteBufferOutputStream2()
    {
        this(null, false);
    }

    public ByteBufferOutputStream2(ByteBufferPool bufferPool, boolean direct)
    {
        _accumulator = new RetainableByteBuffer.DynamicCapacity(bufferPool, direct, -1);
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
        return _accumulator;
    }

    /**
     * @return a newly allocated byte array containing all content written into the OutputStream.
     */
    public byte[] toByteArray()
    {
        return BufferUtil.toArray(_accumulator.getByteBuffer());
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
        _accumulator.append(ByteBuffer.wrap(b, off, len));
    }

    public void write(ByteBuffer buffer)
    {
        _size += buffer.remaining();
        _accumulator.append(buffer);
    }

    public void writeTo(ByteBuffer buffer)
    {
        _accumulator.putTo(buffer);
    }

    public void writeTo(OutputStream out) throws IOException
    {
        try (Blocker.Callback callback = Blocker.callback())
        {
            _accumulator.writeTo(Content.Sink.from(out), false, callback);
            callback.block();
        }
    }

    @Override
    public void close()
    {
        _accumulator.clear();
    }

    @Override
    public synchronized String toString()
    {
        return String.format("%s@%x{size=%d, byteAccumulator=%s}", getClass().getSimpleName(),
            hashCode(), _size, _accumulator);
    }
}
