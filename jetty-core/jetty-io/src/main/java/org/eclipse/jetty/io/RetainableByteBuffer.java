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

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;

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
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Runnable} releaser.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param releaser a {@link Runnable} to call when the buffer is released.
     * @return a {@code RetainableByteBuffer}
     */
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Runnable releaser)
    {
        return new AbstractRetainableByteBuffer(byteBuffer)
        {
            @Override
            public boolean release()
            {
                boolean released = super.release();
                if (released)
                    releaser.run();
                return released;
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

    /** Append byte array to the buffer, potentially limited by capacity.
     * @param bytes the byte array to append
     * @param offset the offset into the array
     * @param length the number of bytes to try to append
     * @return the number of bytes actually appended.
     */
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

    default boolean append(ByteBuffer bytes)
    {
        BufferUtil.append(getByteBuffer(), bytes);
        return !bytes.hasRemaining();
    }

    default boolean append(RetainableByteBuffer bytes)
    {
        return bytes.remaining() == 0 || append(bytes.getByteBuffer());
    }

    default void putTo(ByteBuffer toInfillMode)
    {
        toInfillMode.put(getByteBuffer());
    }

    default void writeTo(OutputStream out) throws IOException
    {
        BufferUtil.writeTo(getByteBuffer(), out);
    }

    default void writeTo(Content.Sink sink, boolean last, Callback callback)
    {
        sink.write(last, getByteBuffer(), callback);
    }

    /**
     * An aggregating {@link RetainableByteBuffer} that may grow when content is appended to it.
     */
    class Aggregator implements RetainableByteBuffer
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final int _growBy;
        private final int _maxCapacity;
        private RetainableByteBuffer _buffer;

        /**
         * Construct an aggregating {@link RetainableByteBuffer} that may grow when content is appended to it.
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxCapacity The maximum requested length of the accumulated buffers or -1 for no limit.
         *                    Note that the pool may provide a buffer that exceeds this capacity.
         */
        public Aggregator(ByteBufferPool pool, boolean direct, int maxCapacity)
        {
            this(pool, direct, -1, maxCapacity);
        }

        /**
         * Construct an aggregating {@link RetainableByteBuffer} that may grow when content is appended to it.
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param growBy the size to grow the buffer by or &lt;= 0 for a heuristic
         * @param maxCapacity The maximum requested length of the accumulated buffers or -1 for no limit.
         *                    Note that the pool may provide a buffer that exceeds this capacity.
         */
        public Aggregator(ByteBufferPool pool, boolean direct, int growBy, int maxCapacity)
        {
            _pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            _direct = direct;
            _maxCapacity = maxCapacity <= 0 ? Integer.MAX_VALUE : maxCapacity;

            if (growBy <= 0)
            {
                _buffer = _pool.acquire(Math.min(1024, _maxCapacity), _direct);
                _growBy = Math.min(_maxCapacity, _buffer.capacity());
            }
            else
            {
                if (growBy > _maxCapacity)
                    throw new IllegalArgumentException("growBy(%d) must be <= maxCapacity(%d)".formatted(growBy, _maxCapacity));

                _growBy = growBy;
                _buffer = _pool.acquire(growBy, _direct);
            }
        }

        @Override
        public boolean canRetain()
        {
            return _buffer.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return _buffer.isRetained();
        }

        @Override
        public void retain()
        {
            _buffer.retain();
        }

        @Override
        public boolean release()
        {
            return _buffer.release();
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
        public boolean append(ByteBuffer bytes)
        {
            ensureSpace(bytes.remaining());
            return RetainableByteBuffer.super.append(bytes);
        }

        @Override
        public boolean append(RetainableByteBuffer bytes)
        {
            ensureSpace(bytes.remaining());
            return RetainableByteBuffer.super.append(bytes);
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

    /**
     * An accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
     * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
     */
    class Accumulator implements RetainableByteBuffer
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxLength;
        private final List<RetainableByteBuffer> _buffers = new ArrayList<>();
        private boolean _canAggregate;

        /**
         * Construct an accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxLength The maximum length of the accumulated buffers or -1 for no limit
         */
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
                    _canAggregate = true;
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
            _canAggregate = false;
            _buffers.clear();
        }

        @Override
        public int append(byte[] bytes, int offset, int length)
        {
            length = ensureMaxLength(length);

            if (_canAggregate)
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
            _canAggregate = true;
            return length;
        }

        @Override
        public boolean append(ByteBuffer bytes)
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
            _canAggregate = true;
            return !bytes.hasRemaining();
        }

        @Override
        public boolean append(RetainableByteBuffer bytes)
        {
            int remaining = bytes.remaining();
            int length = ensureMaxLength(remaining);

            // If we cannot retain, then try aggregation into the last buffer
            if (!bytes.canRetain() && _canAggregate)
            {
                if (_buffers.get(_buffers.size() - 1).append(bytes))
                    return true;
                length -= remaining - bytes.remaining();
                remaining = bytes.remaining();
            }

            // if the length was restricted by maxLength, or we can't retain, then copy into new buffer
            if (length < remaining || !bytes.canRetain())
            {

                RetainableByteBuffer buffer = _pool.acquire(length, _direct);
                buffer.append(bytes);
                bytes.clear();
                _buffers.add(buffer);
                _canAggregate = true;
                return false;
            }

            bytes.retain();
            _buffers.add(bytes);
            _canAggregate = false;
            return true;
        }

        @Override
        public void putTo(ByteBuffer toInfillMode)
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.putTo(toInfillMode);
        }

        @Override
        public void writeTo(OutputStream out) throws IOException
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.writeTo(out);
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            switch (_buffers.size())
            {
                case 0 -> callback.succeeded();
                case 1 -> _buffers.get(0).writeTo(sink, last, callback);
                default -> new IteratingCallback()
                {
                    private int i = 0;

                    @Override
                    protected Action process()
                    {
                        if (i < _buffers.size())
                        {
                            _buffers.get(i).writeTo(sink, ++i == _buffers.size() || !last, this);
                            return Action.SCHEDULED;
                        }
                        return Action.SUCCEEDED;
                    }
                }.iterate();
            }
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
