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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;

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
            {
                acquire();
            }

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
     * Creates a copy of this RetainableByteBuffer that is entirely independent, but
     * backed by the same memory space, i.e.: modifying the ByteBuffer of the original
     * also modifies the ByteBuffer of the copy and vice-versa.
     * @return A copy of this RetainableByteBuffer
     */
    default RetainableByteBuffer copy()
    {
        return new AbstractRetainableByteBuffer(BufferUtil.copy(getByteBuffer()))
        {
            {
                acquire();
            }
        };
    }

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

    /**
     * @return the number of bytes left for appending in the {@code ByteBuffer}
     */
    default int space()
    {
        return capacity() - remaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes left for appending
     */
    default boolean isFull()
    {
        return space() == 0;
    }

    /**
     * Clears the contained byte buffer to be empty in flush mode.
     * @see BufferUtil#clear(ByteBuffer)
     */
    default void clear()
    {
        BufferUtil.clear(getByteBuffer());
    }

    /**
     * Copies the contents of the given byte buffer at the end of this buffer.
     * @param bytes the byte buffer to copy from.
     * @return true if all bytes of the given buffer were copied, false otherwise.
     * @see BufferUtil#append(ByteBuffer, ByteBuffer)
     */
    default boolean append(ByteBuffer bytes)
    {
        BufferUtil.append(getByteBuffer(), bytes);
        return !bytes.hasRemaining();
    }

    /**
     * Copies the contents of the given retainable byte buffer at the end of this buffer.
     * @param bytes the retainable byte buffer to copy from.
     * @return true if all bytes of the given buffer were copied, false otherwise.
     * @see BufferUtil#append(ByteBuffer, ByteBuffer)
     */
    default boolean append(RetainableByteBuffer bytes)
    {
        return bytes.remaining() == 0 || append(bytes.getByteBuffer());
    }

    /**
     * Copies the contents of this retainable byte buffer at the end of the given byte buffer.
     * @param toInfillMode the destination buffer.
     * @see ByteBuffer#put(ByteBuffer)
     */
    default void putTo(ByteBuffer toInfillMode)
    {
        toInfillMode.put(getByteBuffer());
    }

    /**
     * Asynchronously copies the contents of this retainable byte buffer into given sink.
     * @param sink the destination sink.
     * @param last true if this is the last write.
     * @param callback the callback to call upon the write completion.
     * @see org.eclipse.jetty.io.Content.Sink#write(boolean, ByteBuffer, Callback)
     */
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
         * @param maxCapacity The maximum requested length of the accumulated buffers or -1 for 2GB limit.
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
         * @param maxCapacity The maximum requested length of the accumulated buffers or -1 for 2GB limit.
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
                _buffer = _pool.acquire(_growBy, _direct);
            }
        }

        @Override
        public void clear()
        {
            if (isRetained())
            {
                _buffer.release();
                _buffer = _pool.acquire(_growBy, _direct);
            }
            else
            {
                BufferUtil.clear(_buffer.getByteBuffer());
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
        public RetainableByteBuffer copy()
        {
            RetainableByteBuffer buffer = _buffer;
            buffer.retain();
            return new AbstractRetainableByteBuffer(buffer.getByteBuffer().slice())
            {
                {
                    acquire();
                }

                @Override
                public boolean release()
                {
                    if (super.release())
                    {
                        buffer.release();
                        return true;
                    }
                    return false;
                }
            };
        }

        @Override
        public int capacity()
        {
            return Math.max(_buffer.capacity(), _maxCapacity);
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
        private final ReferenceCounter _retainable = new ReferenceCounter(1);
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxLength;
        private final List<RetainableByteBuffer> _buffers = new ArrayList<>();

        /**
         * Construct an accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxLength The maximum length of the accumulated buffers or -1 for 2GB limit
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
                    RetainableByteBuffer combined = copy(true);
                    _buffers.add(combined);
                    yield combined.getByteBuffer();
                }
            };
        }

        @Override
        public RetainableByteBuffer copy()
        {
            return copy(false);
        }

        private RetainableByteBuffer copy(boolean take)
        {
            int length = remaining();
            RetainableByteBuffer combinedBuffer = _pool.acquire(length, _direct);
            ByteBuffer byteBuffer = combinedBuffer.getByteBuffer();
            BufferUtil.flipToFill(byteBuffer);
            for (RetainableByteBuffer buffer : _buffers)
            {
                byteBuffer.put(buffer.getByteBuffer().slice());
                if (take)
                    buffer.release();
            }
            BufferUtil.flipToFlush(byteBuffer, 0);
            if (take)
                _buffers.clear();
            return combinedBuffer;
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the length of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}
         */
        @Override
        public int remaining()
        {
            long remainingLong = remainingLong();
            return remainingLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(remainingLong);
        }

        public long remainingLong()
        {
            long length = 0;
            for (RetainableByteBuffer buffer : _buffers)
                length += buffer.remaining();
            return length;
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the maxLength of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}.
         */
        @Override
        public int capacity()
        {
            long capacityLong = capacityLong();
            return capacityLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(capacityLong);
        }

        public long capacityLong()
        {
            return _maxLength;
        }

        @Override
        public boolean canRetain()
        {
            return _retainable.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return _retainable.isRetained();
        }

        @Override
        public void retain()
        {
            _retainable.retain();
        }

        @Override
        public boolean release()
        {
            if (_retainable.release())
            {
                clear();
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.release();
            _buffers.clear();
        }

        @Override
        public boolean append(ByteBuffer bytes)
        {
            int remaining = bytes.remaining();
            if (remaining == 0)
                return true;

            long currentlyRemaining = _maxLength - remainingLong();
            if (currentlyRemaining >= remaining)
            {
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(bytes.slice());
                bytes.position(bytes.limit());
                _buffers.add(rbb);
                return true;
            }
            else
            {
                ByteBuffer slice = bytes.slice();
                slice.limit((int)(slice.position() + currentlyRemaining));
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(slice);
                bytes.position((int)(bytes.position() + currentlyRemaining));
                _buffers.add(rbb);
                return false;
            }
        }

        @Override
        public boolean append(RetainableByteBuffer retainableBytes)
        {
            ByteBuffer bytes = retainableBytes.getByteBuffer();
            int remaining = bytes.remaining();
            if (remaining == 0)
                return true;

            long currentlyRemaining = _maxLength - remainingLong();
            if (currentlyRemaining >= remaining)
            {
                retainableBytes.retain();
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(bytes.slice(), retainableBytes);
                bytes.position(bytes.limit());
                _buffers.add(rbb);
                return true;
            }
            else
            {
                retainableBytes.retain();
                ByteBuffer slice = bytes.slice();
                slice.limit((int)(slice.position() + currentlyRemaining));
                RetainableByteBuffer rbb = RetainableByteBuffer.wrap(slice, retainableBytes);
                bytes.position((int)(bytes.position() + currentlyRemaining));
                _buffers.add(rbb);
                return false;
            }
        }

        @Override
        public void putTo(ByteBuffer toInfillMode)
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.putTo(toInfillMode);
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            switch (_buffers.size())
            {
                case 0 -> callback.succeeded();
                case 1 -> _buffers.get(0).writeTo(sink, last, callback);
                default -> new IteratingNestedCallback(callback)
                {
                    private int i = 0;

                    @Override
                    protected Action process()
                    {
                        if (i < _buffers.size())
                        {
                            _buffers.get(i).writeTo(sink, last && ++i == _buffers.size(), this);
                            return Action.SCHEDULED;
                        }
                        return Action.SUCCEEDED;
                    }
                }.iterate();
            }
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
