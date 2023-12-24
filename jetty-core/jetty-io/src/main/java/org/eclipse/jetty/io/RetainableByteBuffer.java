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

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * <p>A pooled {@link ByteBuffer} which maintains a reference count that is
 * incremented with {@link #retain()} and decremented with {@link #release()}.</p>
 * <p>The {@code ByteBuffer} is released to a {@link ByteBufferPool}
 * when {@link #release()} is called one more time than {@link #retain()};
 * in such case, the call to {@link #release()} returns {@code true}.</p>
 * <p>A {@code RetainableByteBuffer} can either be:</p>
 * <ul>
 *     <li>in pool; in this case {@link #isRetained()} returns {@code false}
 *     and calling {@link #release()} throws {@link IllegalStateException}</li>
 *     <li>out of pool but not retained; in this case {@link #isRetained()}
 *     returns {@code false} and calling {@link #release()} returns {@code true}</li>
 *     <li>out of pool and retained; in this case {@link #isRetained()}
 *     returns {@code true} and calling {@link #release()} returns {@code false}</li>
 * </ul>
 */
public interface RetainableByteBuffer extends Retainable
{
    /**
     * A Zero-capacity, non-retainable {@code RetainableByteBuffer}.
     */
    RetainableByteBuffer EMPTY = wrap(BufferUtil.EMPTY_BUFFER);

    /**
     * <p>Returns a non-retainable {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer}.</p>
     * <p>Use this method to wrap user-provided {@code ByteBuffer}s, or
     * {@code ByteBuffer}s that hold constant bytes, to make them look
     * like {@code RetainableByteBuffer}s.</p>
     * <p>The returned {@code RetainableByteBuffer} {@link #canRetain()}
     * method always returns {@code false}.</p>
     * <p>{@code RetainableByteBuffer}s returned by this method are not
     * suitable to be wrapped in other {@link Retainable} implementations
     * that may delegate calls to {@link #retain()}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @return a non-retainable {@code RetainableByteBuffer}
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer)
    {
        return new NonRetainableByteBuffer(byteBuffer);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Retainable}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param retainable the associated {@link Retainable}.
     * @return a {@code RetainableByteBuffer}
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new RetainableByteBuffer()
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return byteBuffer;
            }

            @Override
            public boolean isRetained()
            {
                return retainable.isRetained();
            }

            @Override
            public boolean canRetain()
            {
                return retainable.canRetain();
            }

            @Override
            public void retain()
            {
                retainable.retain();
            }

            @Override
            public boolean release()
            {
                return retainable.release();
            }
        };
    }

    /**
     * Get the wrapped, not {@code null}, {@code ByteBuffer}.
     * @return the wrapped, not {@code null}, {@code ByteBuffer}
     */
    ByteBuffer getByteBuffer();

    /**
     * @return whether the {@code ByteBuffer} is direct
     */
    default boolean isDirect()
    {
        return getByteBuffer().isDirect();
    }

    /**
     * @return the number of remaining bytes in the {@code ByteBuffer}
     */
    default int remaining()
    {
        return getByteBuffer().remaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes
     */
    default boolean hasRemaining()
    {
        return getByteBuffer().hasRemaining();
    }

    /**
     * @return the {@code ByteBuffer} capacity
     */
    default int capacity()
    {
        return getByteBuffer().capacity();
    }

    default int space()
    {
        return capacity() - remaining();
    }

    default boolean isFull()
    {
        return remaining() == capacity();
    }

    /**
     * @see BufferUtil#clear(ByteBuffer)
     */
    default void clear()
    {
        BufferUtil.clear(getByteBuffer());
    }

    default int append(byte[] bytes, int offset, int length)
    {
        ByteBuffer to = getByteBuffer();

        int pos = BufferUtil.flipToFill(to);
        try
        {
            length = Math.min(length, to.remaining());
            to.put(bytes, offset, length);
        }
        finally
        {
            BufferUtil.flipToFlush(to, pos);
        }
        return length;
    }

    default void append(ByteBuffer bytes)
    {
        BufferUtil.append(getByteBuffer(), bytes);
    }

    default boolean append(ByteBuffer bytes, Runnable releaser)
    {
        append(bytes);
        if (bytes.hasRemaining())
            return false;
        releaser.run();
        return true;
    }

    default void append(RetainableByteBuffer bytes)
    {
        bytes.writeTo(this);
    }

    default void putTo(ByteBuffer toInfillMode)
    {
        ByteBuffer slice = getByteBuffer().slice();
        toInfillMode.put(slice);
    }

    default boolean writeTo(ByteBuffer to)
    {
        ByteBuffer slice = getByteBuffer().slice();
        BufferUtil.append(to, slice);
        return slice.remaining() == 0;
    }

    default boolean writeTo(RetainableByteBuffer to)
    {
        if (remaining() == 0)
            return true;
        ByteBuffer slice = getByteBuffer().slice();
        to.append(slice);
        return slice.remaining() == 0;
    }

    default void writeTo(OutputStream out) throws IOException
    {
        BufferUtil.writeTo(getByteBuffer(), out);
    }

    default void writeTo(Content.Sink sink, boolean last, Callback callback)
    {
        sink.write(last, getByteBuffer(), callback);
    }

    class Aggregator implements RetainableByteBuffer
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final int _growBy;
        private final int _maxCapacity;
        private RetainableByteBuffer _buffer;

        public Aggregator(ByteBufferPool pool, boolean direct, int growBy, int maxCapacity)
        {
            if (growBy < 0)
                throw new IllegalArgumentException("growBy must be > 0");
            if (maxCapacity > 0 && growBy > maxCapacity)
                throw new IllegalArgumentException("growBy must be < maxSize");

            _pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            _direct = direct;
            _growBy = growBy;
            _maxCapacity = maxCapacity <= 0 ? Integer.MAX_VALUE : maxCapacity;
            _buffer = _pool.acquire(_growBy, _direct);
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return _buffer.getByteBuffer();
        }

        @Override
        public int capacity()
        {
            return Math.max(_buffer.capacity(), _maxCapacity);
        }

        @Override
        public int append(byte[] bytes, int offset, int length)
        {
            ensureSpace(length);
            return RetainableByteBuffer.super.append(bytes, offset, length);
        }

        @Override
        public void append(ByteBuffer bytes)
        {
            ensureSpace(bytes.remaining());
            RetainableByteBuffer.super.append(bytes);
        }

        @Override
        public boolean append(ByteBuffer bytes, Runnable releaser)
        {
            ensureSpace(bytes.remaining());
            return RetainableByteBuffer.super.append(bytes, releaser);
        }

        @Override
        public void append(RetainableByteBuffer bytes)
        {
            ensureSpace(bytes.remaining());
            RetainableByteBuffer.super.append(bytes);
        }

        private void ensureSpace(int spaceNeeded)
        {
            int capacity = _buffer.capacity();
            int space = capacity - _buffer.remaining();
            if (spaceNeeded <= space || capacity >= _maxCapacity)
                return;

            int newCapacity = Math.multiplyExact(1 + Math.addExact(capacity, spaceNeeded) / _growBy,  _growBy);
            if (newCapacity > _maxCapacity)
            {
                newCapacity = Math.addExact(capacity, spaceNeeded - space);
                if (newCapacity > _maxCapacity)
                    newCapacity = _maxCapacity;
            }

            RetainableByteBuffer ensured = _pool.acquire(newCapacity, _direct);
            ensured.append(_buffer);
            _buffer.release();
            _buffer = ensured;
        }
    }

    class Accumulator implements RetainableByteBuffer
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxLength;
        private final List<RetainableByteBuffer> _buffers = new ArrayList<>();
        private boolean _lastAcquiredByUs;

        public Accumulator(ByteBufferPool pool, boolean direct, long maxLength)
        {
            _pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            _direct = direct;
            _maxLength = maxLength < 0 ? Long.MAX_VALUE : maxLength;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return switch (_buffers.size())
            {
                case 0 -> RetainableByteBuffer.EMPTY.getByteBuffer();
                case 1 -> _buffers.get(0).getByteBuffer();
                default ->
                {
                    int length = remaining();
                    RetainableByteBuffer combinedBuffer = _pool.acquire(length, _direct);
                    ByteBuffer byteBuffer = combinedBuffer.getByteBuffer();
                    BufferUtil.flipToFill(byteBuffer);
                    for (RetainableByteBuffer buffer : _buffers)
                    {
                        buffer.putTo(byteBuffer);
                        buffer.clear();
                        buffer.release();
                    }
                    BufferUtil.flipToFlush(byteBuffer, 0);
                    _buffers.clear();
                    _buffers.add(combinedBuffer);
                    _lastAcquiredByUs = true;
                    yield combinedBuffer.getByteBuffer();
                }
            };
        }

        @Override
        public int remaining()
        {
            long length = 0;
            for (RetainableByteBuffer buffer : _buffers)
                length += buffer.remaining();
            return Math.toIntExact(length);
        }

        @Override
        public int capacity()
        {
            return Math.toIntExact(_maxLength);
        }

        @Override
        public boolean release()
        {
            clear();
            return true;
        }

        @Override
        public void clear()
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.release();
            _lastAcquiredByUs = false;
            _buffers.clear();
        }

        @Override
        public int append(byte[] bytes, int offset, int length)
        {
            length = ensureMaxLength(length);

            if (_lastAcquiredByUs)
            {
                RetainableByteBuffer last = _buffers.get(_buffers.size() - 1);
                if (length <= last.space())
                {
                    last.append(bytes, offset, length);
                    return length;
                }
            }

            RetainableByteBuffer buffer = _pool.acquire(length, _direct);
            buffer.append(bytes, offset, length);
            _buffers.add(buffer);
            _lastAcquiredByUs = true;
            return length;
        }

        @Override
        public void append(ByteBuffer bytes)
        {
            int remaining = bytes.remaining();
            int length = ensureMaxLength(remaining);
            // if the length was restricted by maxLength, slice the bytes smaller
            if (length < remaining)
            {
                ByteBuffer slice = bytes.slice();
                slice.limit(slice.position() + length);
                bytes.position(bytes.position() + length);
                bytes = slice;
            }
            RetainableByteBuffer buffer = _pool.acquire(length, _direct);
            buffer.append(bytes);
            _buffers.add(buffer);
            _lastAcquiredByUs = true;
        }

        @Override
        public boolean append(ByteBuffer bytes, Runnable releaser)
        {
            int remaining = bytes.remaining();
            int length = ensureMaxLength(remaining);
            // if the length was restricted by maxLength, we can't split the releaser so append nothing
            if (length < remaining)
                return false;

            _buffers.add(new RetainableByteBuffer()
            {
                private final AtomicReference<Runnable> _releaser = new AtomicReference<>(releaser);
                @Override
                public ByteBuffer getByteBuffer()
                {
                    return bytes;
                }

                @Override
                public boolean release()
                {
                    Runnable releaser = _releaser.getAndSet(null);
                    if (releaser == null)
                        return false;
                    releaser.run();
                    return true;
                }
            });
            _lastAcquiredByUs = false;
            return true;
        }

        @Override
        public void append(RetainableByteBuffer bytes)
        {
            int remaining = bytes.remaining();
            int length = ensureMaxLength(remaining);
            // if the length was restricted by maxLength, or we can't retain, then copy into new buffer
            if (length < remaining || !bytes.canRetain())
            {
                RetainableByteBuffer buffer = _pool.acquire(length, _direct);
                bytes.writeTo(buffer);
                bytes.clear();
                _buffers.add(buffer);
                _lastAcquiredByUs = true;
                return;
            }

            bytes.retain();
            _buffers.add(bytes);
            _lastAcquiredByUs = false;
        }

        @Override
        public void putTo(ByteBuffer toInfillMode)
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.putTo(toInfillMode);
        }

        @Override
        public boolean writeTo(ByteBuffer to)
        {
            return RetainableByteBuffer.super.writeTo(to);
        }

        @Override
        public boolean writeTo(RetainableByteBuffer to)
        {
            return RetainableByteBuffer.super.writeTo(to);
        }

        @Override
        public void writeTo(OutputStream out) throws IOException
        {
            RetainableByteBuffer.super.writeTo(out);
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            RetainableByteBuffer.super.writeTo(sink, last, callback);
        }

        private int ensureMaxLength(int increment)
        {
            long length = 0;
            for (RetainableByteBuffer buffer : _buffers)
                length += buffer.remaining();

            long newLength = Math.addExact(length, increment);
            if (newLength <= _maxLength)
                return increment;

            return Math.toIntExact(_maxLength - length);
        }
    }

    /**
     * A wrapper for {@link RetainableByteBuffer} instances
     */
    class Wrapper extends Retainable.Wrapper implements RetainableByteBuffer
    {
        public Wrapper(RetainableByteBuffer wrapped)
        {
            super(wrapped);
        }

        public RetainableByteBuffer getWrapped()
        {
            return (RetainableByteBuffer)super.getWrapped();
        }

        @Override
        public boolean isRetained()
        {
            return getWrapped().isRetained();
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return getWrapped().getByteBuffer();
        }

        @Override
        public boolean isDirect()
        {
            return getWrapped().isDirect();
        }

        @Override
        public int remaining()
        {
            return getWrapped().remaining();
        }

        @Override
        public boolean hasRemaining()
        {
            return getWrapped().hasRemaining();
        }

        @Override
        public int capacity()
        {
            return getWrapped().capacity();
        }

        @Override
        public void clear()
        {
            getWrapped().clear();
        }
    }
}
