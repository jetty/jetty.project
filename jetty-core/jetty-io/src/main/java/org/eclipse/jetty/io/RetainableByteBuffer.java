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

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
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

            @Override
            public String toDetailString()
            {
                StringBuilder buf = new StringBuilder();
                buf.append(getClass().getSimpleName());
                buf.append("@");
                buf.append(Integer.toHexString(System.identityHashCode(this)));
                buf.append("[r=");
                buf.append(remaining());
                buf.append("/");
                buf.append(capacity());
                buf.append(",");
                buf.append(retainable);
                buf.append("]");
                if (retainable.canRetain())
                {
                    buf.append("={");
                    appendDebugString(buf, this);
                    buf.append("}");
                }
                return buf.toString();
            }

            @Override
            public String toString()
            {
                return toDetailString();
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
     * Appends and consumes the contents of this buffer to the passed buffer, limited by the capacity of the target buffer.
     * @param buffer The buffer to append bytes to, whose limit will be updated.
     * @return {@code true} if all bytes in this buffer are able to be appended.
     * @see #putTo(ByteBuffer)
     */
    default boolean appendTo(ByteBuffer buffer)
    {
        return remaining() == BufferUtil.append(buffer, getByteBuffer());
    }

    /**
     * Appends and consumes the contents of this buffer to the passed buffer, limited by the capacity of the target buffer.
     * @param buffer The buffer to append bytes to, whose limit will be updated.
     * @return {@code true} if all bytes in this buffer are able to be appended.
     * @see #putTo(ByteBuffer)
     */
    default boolean appendTo(RetainableByteBuffer buffer)
    {
        return appendTo(buffer.getByteBuffer());
    }

    /**
     * Creates a deep copy of this RetainableByteBuffer that is entirely independent
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
     * Consumes and returns a byte from this RetainableByteBuffer
     *
     * @return the byte
     * @throws BufferUnderflowException if the buffer is empty.
     */
    default byte get() throws BufferUnderflowException
    {
        return getByteBuffer().get();
    }

    /**
     * Consumes and copies the bytes from this RetainableByteBuffer to the given byte array.
     *
     * @param bytes the byte array to copy the bytes into
     * @param offset the offset within the byte array
     * @param length the maximum number of bytes to copy
     * @return the number of bytes actually copied
     */
    default int get(byte[] bytes, int offset, int length)
    {
        ByteBuffer b = getByteBuffer();
        if (b == null || !b.hasRemaining())
            return 0;
        length = Math.min(length, b.remaining());
        b.get(bytes, offset, length);
        return length;
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
     * @return whether the {@code ByteBuffer} has remaining bytes left for reading
     */
    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    /**
     * @return whether the {@code ByteBuffer} has remaining bytes left for appending
     */
    default boolean isFull()
    {
        return space() == 0;
    }

    /**
     * Consumes and puts the contents of this retainable byte buffer at the end of the given byte buffer.
     * @param toInfillMode the destination buffer, whose position is updated.
     * @throws BufferOverflowException – If there is insufficient space in this buffer for the remaining bytes in the source buffer
     * @see ByteBuffer#put(ByteBuffer)
     */
    default void putTo(ByteBuffer toInfillMode) throws BufferOverflowException
    {
        toInfillMode.put(getByteBuffer());
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
     * @see BufferUtil#clear(ByteBuffer)
     */
    default void clear()
    {
        BufferUtil.clear(getByteBuffer());
    }

    /**
     * <p>Skips, advancing the ByteBuffer position, the given number of bytes.</p>
     *
     * @param length the maximum number of bytes to skip
     * @return the number of bytes actually skipped
     */
    default long skip(long length)
    {
        if (length == 0)
            return 0;
        ByteBuffer byteBuffer = getByteBuffer();
        length = Math.min(byteBuffer.remaining(), length);
        byteBuffer.position(byteBuffer.position() + Math.toIntExact(length));
        return length;
    }

    /**
     * Get a slice of the buffer.
     * @return A sliced {@link RetainableByteBuffer} sharing this buffers data and reference count, but
     *         with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice()
    {
        if (canRetain())
            retain();
        return RetainableByteBuffer.wrap(getByteBuffer().slice(), this);
    }

    /**
     * Get a partial slice of the buffer.
     * @param length The number of bytes to slice.
     * @return A sliced {@link RetainableByteBuffer} sharing the first {@code length} bytes of this buffers data and
     * reference count, but with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice(long length)
    {
        if (canRetain())
            retain();
        ByteBuffer slice = getByteBuffer().slice();
        slice.limit(slice.position() + Math.toIntExact(length));
        return RetainableByteBuffer.wrap(slice, this);
    }

    /**
     * @return the number of bytes left for appending in the {@code ByteBuffer}
     */
    default int space()
    {
        return capacity() - remaining();
    }

    /**
     * Asynchronously writes and consumes the contents of this retainable byte buffer into given sink.
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
     * Convert Buffer to a detail debug string of pointers and content
     *
     * @return A string showing the pointers and content of the buffer
     */
    default String toDetailString()
    {
        StringBuilder buf = new StringBuilder();

        buf.append(getClass().getSimpleName());
        buf.append("@");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append("[r/c=");
        buf.append(remaining());
        buf.append("/");
        buf.append(capacity());
        buf.append("]={");
        appendDebugString(buf, this);
        buf.append("}");
        return buf.toString();
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

        @Override
        public boolean canRetain()
        {
            return getWrapped().canRetain();
        }

        @Override
        public void retain()
        {
            getWrapped().retain();
        }

        @Override
        public boolean release()
        {
            return getWrapped().release();
        }

        @Override
        public String toString()
        {
            return "%s@%x{%s}".formatted(getClass().getSimpleName(), hashCode(), getWrapped().toString());
        }

        @Override
        public boolean appendTo(ByteBuffer buffer)
        {
            return getWrapped().appendTo(buffer);
        }

        @Override
        public boolean appendTo(RetainableByteBuffer buffer)
        {
            return getWrapped().appendTo(buffer);
        }

        @Override
        public RetainableByteBuffer copy()
        {
            return getWrapped().copy();
        }

        @Override
        public int get(byte[] bytes, int offset, int length)
        {
            return getWrapped().get(bytes, offset, length);
        }

        @Override
        public boolean isEmpty()
        {
            return getWrapped().isEmpty();
        }

        @Override
        public boolean isFull()
        {
            return getWrapped().isFull();
        }

        @Override
        public void putTo(ByteBuffer toInfillMode) throws BufferOverflowException
        {
            getWrapped().putTo(toInfillMode);
        }

        @Override
        public long skip(long length)
        {
            return getWrapped().skip(length);
        }

        @Override
        public RetainableByteBuffer slice()
        {
            return getWrapped().slice();
        }

        @Override
        public int space()
        {
            return getWrapped().space();
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            getWrapped().writeTo(sink, last, callback);
        }
    }

    /**
     * A {@link RetainableByteBuffer} that may be appended to, either copying or retain the other buffers depending on heuristics.
     */
    class Appendable implements RetainableByteBuffer
    {
        private final Retainable _retainable = new ReferenceCounter();
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxSize;
        private final List<RetainableByteBuffer> _buffers;
        private final int _aggregationSize;
        private final int _minRetainSize;
        private RetainableByteBuffer _aggregate;

        /**
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         */
        public Appendable(ByteBufferPool pool, boolean direct, long maxSize)
        {
            this(pool, direct, maxSize, -1, -1);
        }

        /**
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         * @param aggregationSize The default size of aggregation buffers; or 0 for no aggregation; or -1 for a default size
         */
        public Appendable(ByteBufferPool pool, boolean direct, long maxSize, int aggregationSize)
        {
            this(pool, direct, maxSize, aggregationSize, -1);
        }

        /**
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         * @param aggregationSize The default size of aggregation buffers; or 0 for no aggregation; or -1 for a default size
         * @param minRetainSize The minimal size of a {@link RetainableByteBuffer} before it will be retained; or 0 to never retain; or -1 for a default value;
         */
        public Appendable(ByteBufferPool pool, boolean direct, long maxSize, int aggregationSize, int minRetainSize)
        {
            this(new ArrayList<>(), pool, direct, maxSize, aggregationSize, minRetainSize);
        }

        private Appendable(List<RetainableByteBuffer> buffers, ByteBufferPool pool, boolean direct, long maxSize, int aggregationSize, int minRetainSize)
        {
            _pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            _direct = direct;
            _maxSize = maxSize < 0 ? Long.MAX_VALUE : maxSize;
            _buffers = buffers;

            if (aggregationSize < 0)
            {
                _aggregationSize = (int)Math.min(_maxSize, 8192L);
            }
            else
            {
                if (aggregationSize > _maxSize)
                    throw new IllegalArgumentException("aggregationSize(%d) must be <= maxCapacity(%d)".formatted(aggregationSize, _maxSize));
                _aggregationSize = aggregationSize;
            }
            _minRetainSize = minRetainSize < 0 ? Math.min(128, _aggregationSize) : minRetainSize;
            if (_minRetainSize != 0 && _aggregationSize == 0)
                throw new IllegalArgumentException("must always retain if cannot aggregate");
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
        public byte get() throws BufferUnderflowException
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                    continue;
                }

                byte b = buffer.get();
                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                }
                return b;
            }
            throw new BufferUnderflowException();
        }

        @Override
        public int get(byte[] bytes, int offset, int length)
        {
            int got = 0;
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); length > 0 && i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                int l = buffer.get(bytes, offset, length);
                got += l;
                offset += l;
                length -= l;

                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                }
            }
            return got;
        }

        @Override
        public boolean isDirect()
        {
            return _direct;
        }

        @Override
        public boolean hasRemaining()
        {
            for (RetainableByteBuffer rbb : _buffers)
                if (!rbb.isEmpty())
                    return true;
            return false;
        }

        @Override
        public long skip(long length)
        {
            long skipped = 0;
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); length > 0 && i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                long skip = buffer.skip(length);
                skipped += skip;
                length -= skip;

                if (buffer.isEmpty())
                {
                    buffer.release();
                    i.remove();
                }
            }
            return skipped;
        }

        @Override
        public RetainableByteBuffer slice()
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.slice());
            retain();
            Appendable parent = this;
            return new Appendable(buffers, _pool, _direct, _maxSize, _aggregationSize, _minRetainSize)
            {
                @Override
                public boolean release()
                {
                    if (super.release())
                    {
                        parent.release();
                        return true;
                    }
                    return false;
                }
            };
        }

        @Override
        public RetainableByteBuffer slice(long length)
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
            {
                int l = rbb.remaining();

                if (l > length)
                {
                    buffers.add(rbb.slice(length));
                    break;
                }

                buffers.add(rbb.slice());
                length -= l;
            }

            retain();
            Appendable parent = this;
            return new Appendable(buffers, _pool, _direct, _maxSize, _aggregationSize, _minRetainSize)
            {
                @Override
                public boolean release()
                {
                    if (super.release())
                    {
                        parent.release();
                        return true;
                    }
                    return false;
                }
            };
        }

        @Override
        public int space()
        {
            long space = spaceLong();
            if (space > Integer.MAX_VALUE)
                return Integer.MAX_VALUE;
            return (int)space;
        }

        public long spaceLong()
        {
            return capacityLong() - remainingLong();
        }

        @Override
        public boolean isFull()
        {
            return spaceLong() <= 0;
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
            return _maxSize;
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

        public boolean append(ByteBuffer bytes)
        {
            // handle empty appends
            if (bytes == null)
                return true;
            int length = bytes.remaining();
            if (length == 0)
                return true;

            // If we have an existing aggregation buffer, try appending to it
            if (_aggregate != null)
            {
                if (BufferUtil.append(_aggregate.getByteBuffer(), bytes) == length)
                    return true;

                // we were limited by the capacity of the buffer, fall through to trying to allocate another
                _aggregate = null;
            }

            // are we full?
            long size = remainingLong();
            long space = _maxSize - size;
            if (space <= 0)
                return false;

            // acquire a new buffer to aggregate into
            int newCapacity = Math.max(length, _aggregationSize);
            _aggregate = _pool.acquire(newCapacity, _direct);

            // If we were given a buffer larger than the space available, then adjust the capacity
            if (_aggregate.capacity() > space)
            {
                ByteBuffer byteBuffer = _aggregate.getByteBuffer();
                int limit = byteBuffer.limit();
                byteBuffer.limit(limit + Math.toIntExact(space));
                byteBuffer = byteBuffer.slice();
                byteBuffer.limit(limit);
                _aggregate = RetainableByteBuffer.wrap(byteBuffer, _aggregate);
            }

            _buffers.add(_aggregate);

            return BufferUtil.append(_aggregate.getByteBuffer(), bytes) == length;
        }

        public boolean append(RetainableByteBuffer retainableBytes)
        {
            // handle empty appends
            if (retainableBytes == null)
                return true;
            long length = retainableBytes.remaining();
            if (length == 0)
                return true;

            // If we are already aggregating, and the content will fit, then just aggregate
            if (_aggregate != null && _aggregate.space() >= length)
            {
                BufferUtil.append(_aggregate.getByteBuffer(), retainableBytes.getByteBuffer());
                return true;
            }

            // If the content is a tiny part of the retainable, then better to aggregate rather than accumulate
            if (length < _minRetainSize)
                return append(retainableBytes.getByteBuffer());

            // We will accumulate, so stop any further aggregation without allocating a new aggregate buffer;
            _aggregate = null;

            // Do we have space?
            long space = _maxSize - remainingLong();

            if (space >= length)
            {
                // We have space, so add a retained slice;
                _buffers.add(retainableBytes.slice());
                retainableBytes.skip(length);
                return true;
            }

            // Are we full?
            if (space == 0)
                return false;

            // Add a space limited retained slice of the buffer
            length = space;
            _buffers.add(retainableBytes.slice(length));
            retainableBytes.skip(length);
            return false;
        }

        @Override
        public void putTo(ByteBuffer toInfillMode)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                buffer.putTo(toInfillMode);
                buffer.release();
                i.remove();
            }
        }

        @Override
        public boolean appendTo(ByteBuffer to)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (!buffer.appendTo(to))
                    return false;
                buffer.release();
                i.remove();
            }
            return true;
        }

        @Override
        public boolean appendTo(RetainableByteBuffer to)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (!buffer.appendTo(to))
                    return false;
                buffer.release();
                i.remove();
            }
            return true;
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            switch (_buffers.size())
            {
                case 0 -> callback.succeeded();
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    buffer.writeTo(sink, last, Callback.from(() ->
                    {
                        if (!buffer.hasRemaining())
                        {
                            buffer.release();
                            _buffers.clear();
                        }
                    }, callback));
                }
                default -> new IteratingNestedCallback(callback)
                {
                    boolean _lastWritten;

                    @Override
                    protected Action process()
                    {
                        while (true)
                        {
                            if (_buffers.isEmpty())
                            {
                                if (last && !_lastWritten)
                                {
                                    _lastWritten = true;
                                    sink.write(true, BufferUtil.EMPTY_BUFFER, this);
                                    return Action.SCHEDULED;
                                }
                                return Action.SUCCEEDED;
                            }

                            RetainableByteBuffer buffer = _buffers.get(0);
                            if (buffer.hasRemaining())
                            {
                                _lastWritten = last && _buffers.size() == 1;
                                buffer.writeTo(sink, _lastWritten, this);
                                return Action.SCHEDULED;
                            }

                            buffer.release();
                            _buffers.remove(0);
                        }
                    }
                }.iterate();
            }
        }

        @Override
        public String toString()
        {
            StringBuilder buf = new StringBuilder();

            buf.append(getClass().getSimpleName());
            buf.append("@");
            buf.append(Integer.toHexString(System.identityHashCode(this)));
            buf.append("[r/c=");
            buf.append(remainingLong());
            buf.append("/");
            buf.append(capacityLong());
            buf.append(",gb=");
            buf.append(_aggregationSize);
            buf.append(",ma=");
            buf.append(_minRetainSize);
            buf.append(",");
            buf.append(_retainable);
            buf.append("]");
            if (_retainable.canRetain())
            {
                buf.append("={");
                appendDebugString(buf, this);
                buf.append("}");
            }
            return buf.toString();
        }
    }

    static void appendDebugString(StringBuilder buf, RetainableByteBuffer buffer)
    {
        // Take a slice so we can adjust the limit
        RetainableByteBuffer slice = buffer.slice();
        try
        {
            buf.append("<<<");

            int size = slice.remaining();

            int skip = Math.max(0, size - 32);

            int bytes = 0;
            while (slice.remaining() > 0)
            {
                BufferUtil.appendDebugByte(buf, slice.get());
                if (skip > 0 && ++bytes == 16)
                {
                    buf.append("...");
                    slice.skip(skip);
                }
            }
            buf.append(">>>");
        }
        catch (Throwable x)
        {
            buf.append("!!concurrent mod!!");
        }
        finally
        {
            slice.release();
        }
    }
}
