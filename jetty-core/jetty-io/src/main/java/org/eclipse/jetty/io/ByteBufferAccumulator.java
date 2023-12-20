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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * Accumulates data into a list of ByteBuffers which can then be combined into a single buffer or written to an OutputStream.
 * The buffer list automatically grows as data is written to it, the buffers are taken from the
 * supplied {@link ByteBufferPool} or freshly allocated if one is not supplied.
 * <p>
 * The method {@link #accessInternalBuffer(int, int)} can be used access a buffer that can be written to directly as the last buffer list,
 * if there is less than a certain amount of space available in that buffer then a new one will be allocated and returned instead.
 */
public class ByteBufferAccumulator implements AutoCloseable
{
    private final List<RetainableByteBuffer> _buffers = new ArrayList<>();
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final long _maxLength;

    public ByteBufferAccumulator()
    {
        this(null, false);
    }

    public ByteBufferAccumulator(ByteBufferPool bufferPool, boolean direct)
    {
        this(bufferPool, direct, -1);
    }

    public ByteBufferAccumulator(ByteBufferPool bufferPool, boolean direct, long maxLength)
    {
        _bufferPool = (bufferPool == null) ? new ByteBufferPool.NonPooling() : bufferPool;
        _direct = direct;
        _maxLength = maxLength;
    }

    /**
     * Get the amount of bytes which have been accumulated.
     * This will add up the remaining of each buffer in the accumulator.
     * @return the total length of the content in the accumulator.
     */
    public long getLength()
    {
        long length = 0;
        for (RetainableByteBuffer buffer : _buffers)
            length = Math.addExact(length, buffer.remaining());
        return length;
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     * @deprecated Use {@link #copyBuffer(ByteBuffer)}, {@link #copyBytes(byte[], int, int)} or {@link #addBuffer(RetainableByteBuffer)}
     */
    @Deprecated
    public RetainableByteBuffer ensureBuffer(int minAllocationSize)
    {
        return accessInternalBuffer(1, minAllocationSize);
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minSize the smallest amount of remaining space before a new buffer is allocated.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     * @deprecated Use {@link #copyBuffer(ByteBuffer)}, {@link #copyBytes(byte[], int, int)} or {@link #addBuffer(RetainableByteBuffer)}
     */
    @Deprecated
    public RetainableByteBuffer ensureBuffer(int minSize, int minAllocationSize)
    {
        return accessInternalBuffer(minSize, minAllocationSize);
    }

    /**
     * Get the last buffer of the accumulator, this can be written to directly to avoid copying into the accumulator.
     * @param minSize the smallest amount of remaining space before a new buffer is allocated.
     * @param minAllocationSize new buffers will be allocated to have at least this size.
     * @return a buffer with at least {@code minSize} space to write into.
     */
    RetainableByteBuffer accessInternalBuffer(int minSize, int minAllocationSize)
    {
        RetainableByteBuffer buffer = _buffers.isEmpty() ? null : _buffers.get(_buffers.size() - 1);
        if (buffer == null || BufferUtil.space(buffer.getByteBuffer()) < minSize)
        {
            buffer = _bufferPool.acquire(minAllocationSize, _direct);
            _buffers.add(buffer);
        }
        return buffer;
    }

    private boolean maxLengthExceeded(long extraLength)
    {
        if (_maxLength < 0)
            return false;
        long length = Math.addExact(getLength(), extraLength);
        return length > _maxLength;
    }

    /**
     * Copy bytes from an array into this accumulator
     * @param buf The byte array to copy from
     * @param offset The offset into the array to start copying from
     * @param length The number of bytes to copy
     * @throws IllegalArgumentException if the max length is exceeded.
     */
    public void copyBytes(byte[] buf, int offset, int length)
        throws IllegalArgumentException
    {
        copyBuffer(BufferUtil.toBuffer(buf, offset, length));
    }

    /**
     * Copy bytes from a {@link ByteBuffer} into this accumulator
     * @param buffer The {@code ByteBuffer} to copy from, whose position is updated.
     * @throws IllegalArgumentException if the max length is exceeded.
     */
    public void copyBuffer(ByteBuffer buffer)
        throws IllegalArgumentException
    {
        if (maxLengthExceeded(buffer.remaining()))
            throw new IllegalArgumentException("maxLength exceeded");
        while (buffer.hasRemaining())
        {
            RetainableByteBuffer target = accessInternalBuffer(1, buffer.remaining());
            ByteBuffer byteBuffer = target.getByteBuffer();
            int pos = BufferUtil.flipToFill(byteBuffer);
            BufferUtil.put(buffer, byteBuffer);
            BufferUtil.flipToFlush(byteBuffer, pos);
        }
    }

    /**
     * Add (without copying if possible) a {@link RetainableByteBuffer}
     * @param buffer The {@code RetainableByteBuffer} to add to the accumulator, which will either be
     *               {@link Retainable#retain() retained} or copied if it {@link Retainable#canRetain() cannot be retained}.
     *               The buffers position will not be updated.
     */
    public void addBuffer(RetainableByteBuffer buffer)
    {
        if (buffer != null && buffer.hasRemaining())
        {
            if (maxLengthExceeded(buffer.remaining()))
                throw new IllegalArgumentException("maxLength exceeded");

            if (buffer.canRetain())
            {
                buffer.retain();
                _buffers.add(buffer);
            }
            else
            {
                copyBuffer(buffer.getByteBuffer().slice());
            }
        }
    }

    /**
     * Add without copying a {@link ByteBuffer}.
     * @param buffer The {@code ByteBuffer} to add.
     * @param releaseCallback A callback that is {@link Callback#succeeded() succeeded} when the buffer is no longer held,
     *                        or {@link Callback#failed(Throwable) failed} if the {@link #fail(Throwable)} method is called.
     */
    public void addBuffer(ByteBuffer buffer, Callback releaseCallback)
    {
        if (BufferUtil.isEmpty(buffer))
        {
            releaseCallback.succeeded();
        }
        else
        {
            _buffers.add(new FailableRetainableByteBuffer(buffer, releaseCallback));
        }
    }

    /**
     * Aggregates the given ByteBuffer into the last buffer of this accumulation, growing the buffer in size if
     * necessary. This copies bytes up to the specified maximum size into the
     * last buffer in the accumulation, at which time this method returns {@code true}
     * and {@link #takeRetainableByteBuffer()} must be called for this method to accept aggregating again.
     * @param source the buffer to copy into this aggregator; its position is updated according to
     * the number of aggregated bytes
     * @return true if the aggregator's buffer is full and should be taken, false otherwise
     */
    public boolean aggregate(ByteBuffer source)
    {
        if (BufferUtil.isEmpty(source))
            return false;

        // How much of the buffer can be aggregated?
        int toCopy = source.remaining();
        boolean full = false;
        if (_maxLength >= 0)
        {
            long space = _maxLength - getLength();
            if (space == 0)
                return true;
            if (toCopy >= space)
            {
                full = true;
                toCopy = (int)space;
            }
        }

        // Do we need to allocate a new buffer?
        RetainableByteBuffer buffer = _buffers.isEmpty() ? null : _buffers.get(_buffers.size() - 1);
        if (buffer == null || buffer.isRetained() || BufferUtil.space(buffer.getByteBuffer()) < toCopy)
        {
            int prefix = buffer == null ? 0 : buffer.remaining();
            int minSize = prefix + toCopy;
            int allocSize = (int)Math.min(_maxLength, ceilToNextPowerOfTwo(minSize));
            RetainableByteBuffer next = _bufferPool.acquire(allocSize, _direct);

            if (prefix > 0)
                BufferUtil.append(next.getByteBuffer(), buffer.getByteBuffer());

            if (buffer == null)
                _buffers.add(next);
            else
            {
                _buffers.set(_buffers.size() - 1, next);
                buffer.release();
            }
            buffer = next;
        }

        // Aggregate the bytes into the prepared space
        ByteBuffer target = buffer.getByteBuffer();
        int p = BufferUtil.flipToFill(target);
        int tp = target.position();
        target.put(tp, source, 0, toCopy);
        source.position(source.position() + toCopy);
        target.position(tp + toCopy);
        BufferUtil.flipToFlush(target, p);

        return full;
    }

    private static int ceilToNextPowerOfTwo(int val)
    {
        int result = 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(val - 1));
        return result > 0 ? result : Integer.MAX_VALUE;
    }

    /**
     * Take the combined buffer containing all content written to the accumulator.
     * The caller is responsible for releasing this {@link RetainableByteBuffer}.
     * @return a buffer containing all content written to the accumulator.
     * @see #toRetainableByteBuffer()
     */
    public RetainableByteBuffer takeRetainableByteBuffer()
    {
        return switch (_buffers.size())
        {
            case 0 -> RetainableByteBuffer.EMPTY;
            case 1 ->
            {
                RetainableByteBuffer buffer = _buffers.get(0);
                _buffers.clear();
                yield buffer;
            }
            default ->
            {
                long length = getLength();
                if (length > Integer.MAX_VALUE)
                    throw new IllegalStateException("too large for ByteBuffer");
                RetainableByteBuffer combinedBuffer = _bufferPool.acquire((int)length, _direct);
                ByteBuffer byteBuffer = combinedBuffer.getByteBuffer();
                BufferUtil.clearToFill(byteBuffer);
                for (RetainableByteBuffer buffer : _buffers)
                {
                    byteBuffer.put(buffer.getByteBuffer());
                    buffer.release();
                }
                BufferUtil.flipToFlush(byteBuffer, 0);
                _buffers.clear();
                yield combinedBuffer;
            }
        };
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
        long length = getLength();
        if (length == 0)
            return new byte[0];
        if (length > Integer.MAX_VALUE)
            throw new IllegalStateException("too large for array");
        byte[] bytes = new byte[(int)length];
        ByteBuffer buffer = BufferUtil.toBuffer(bytes);
        BufferUtil.clear(buffer);
        writeTo(buffer);
        return bytes;
    }

    public byte[] takeByteArray()
    {
        byte[] out = toByteArray();
        close();
        return out;
    }

    public void writeTo(ByteBuffer byteBuffer)
    {
        int pos = BufferUtil.flipToFill(byteBuffer);
        for (RetainableByteBuffer buffer : _buffers)
            byteBuffer.put(buffer.getByteBuffer().slice());
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

    public void fail(Throwable cause)
    {
        _buffers.forEach(r ->
        {
            if (r instanceof FailableRetainableByteBuffer frbb)
                frbb.fail(cause);
            else
                r.release();
        });
        _buffers.clear();
    }

    private static class FailableRetainableByteBuffer implements RetainableByteBuffer
    {
        private final ByteBuffer _buffer;
        private final AtomicReference<Callback> _callback;

        private FailableRetainableByteBuffer(ByteBuffer buffer, Callback callback)
        {
            _buffer = buffer;
            _callback = new AtomicReference<>(callback);
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return _buffer;
        }

        @Override
        public boolean release()
        {
            Callback callback = _callback.getAndSet(null);
            if (callback == null)
                return false;
            callback.succeeded();
            return true;
        }

        public void fail(Throwable cause)
        {
            Callback callback = _callback.getAndSet(null);
            if (callback != null)
                callback.failed(cause);
        }
    }
}
