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
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.io.internal.NonRetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;

/**
 * <p>A {@link ByteBuffer} which maintains a reference count that is
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
 * <p>The API read-only, even if the underlying {@link ByteBuffer} is read-write.  The {@link Appendable} sub-interface
 * provides a read-write API.  All provided implementation implement {@link Appendable}, but may only present as
 * a {@code RetainableByteBuffer}.  The {@link #asAppendable()} method can be used to access the read-write version of the
 * API.</p>
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
        return new FixedCapacity(byteBuffer, retainable);
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
        return new FixedCapacity(byteBuffer)
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
     * @return An {@link Appendable} representation of this buffer with same data and pointers.
     * @throws ReadOnlyBufferException If the buffer is not {@link Appendable} or the backing {@link ByteBuffer} is
     * {@link ByteBuffer#isReadOnly() read-only}.
     */
    default Appendable asAppendable() throws ReadOnlyBufferException
    {
        if (this instanceof Appendable appendable)
            return appendable;
        return new FixedCapacity(getByteBuffer(), this);
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
        ByteBuffer byteBuffer = getByteBuffer();
        ByteBuffer copy = BufferUtil.copy(byteBuffer);
        return new FixedCapacity(copy);
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
     * @throws BufferOverflowException if the contents is too large for a single {@link ByteBuffer}
     */
    ByteBuffer getByteBuffer() throws BufferOverflowException;

    /**
     * @return whether the {@code ByteBuffer} is direct
     */
    default boolean isDirect()
    {
        return getByteBuffer().isDirect();
    }

    /**
     * @return the number of remaining bytes in the {@code ByteBuffer}
     * @see #size()
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
     * @return whether the {@code ByteBuffer} has remaining bytes left for reading
     */
    default boolean isEmpty()
    {
        return !hasRemaining();
    }

    /**
     * @return the number of remaining bytes in the {@code ByteBuffer}
     * @see #remaining()
     */
    default long size()
    {
        return remaining();
    }

    /**
     * @return the maximum size in bytes.
     * @see #size()
     */
    default long maxSize()
    {
        return capacity();
    }

    /**
     * @return the capacity
     * @see #maxSize()
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
     * @param length The number of bytes to slice, which may contain some byte beyond the limit and less than the capacity
     * @return A sliced {@link RetainableByteBuffer} sharing the first {@code length} bytes of this buffers data and
     * reference count, but with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice(long length)
    {
        if (canRetain())
            retain();

        int size = remaining();
        ByteBuffer byteBuffer = getByteBuffer();
        int limit = byteBuffer.limit();

        if (length <= size)
        {
            byteBuffer.limit(byteBuffer.position() + Math.toIntExact(length));
            ByteBuffer slice = byteBuffer.slice();
            byteBuffer.limit(limit);
            return RetainableByteBuffer.wrap(slice, this);
        }
        else
        {
            length = Math.min(length, byteBuffer.capacity() - byteBuffer.position());
            byteBuffer.limit(byteBuffer.position() + Math.toIntExact(length));
            ByteBuffer slice = byteBuffer.slice();
            byteBuffer.limit(limit);
            slice.limit(size);
            return RetainableByteBuffer.wrap(slice, this);
        }
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
     * Extends the {@link RetainableByteBuffer} API with optimized append methods.
     */
    interface Appendable extends RetainableByteBuffer
    {
        /**
         * @return the number of bytes left for appending in the {@code ByteBuffer}
         */
        default long space()
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
         * Copies the contents of the given byte buffer to the end of this buffer.
         * Copies can be avoided by {@link RetainableByteBuffer#wrap(ByteBuffer) wrapping} the buffer and
         * calling {@link #append(RetainableByteBuffer)}
         * @param bytes the byte buffer to copy from, which is consumed.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if the buffer is read only or {@link #isRetained() is retained}
         */
        default boolean append(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            if (isRetained())
                throw new ReadOnlyBufferException();
            BufferUtil.append(getByteBuffer(), bytes);
            return !bytes.hasRemaining();
        }

        /**
         * Retain or copy the contents of the given retainable byte buffer to the end of this buffer.
         * The implementation will heuristically decide to retain or copy the contents.
         * @param bytes the retainable byte buffer to copy from, which is consumed.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if the buffer is read only or {@link #isRetained() is retained}
         */
        default boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            if (isRetained())
                throw new ReadOnlyBufferException();
            return bytes.remaining() == 0 || append(bytes.getByteBuffer());
        }
    }

    /**
     * A wrapper for {@link RetainableByteBuffer} instances
     */
    class Wrapper extends Retainable.Wrapper implements RetainableByteBuffer.Appendable
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
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            getWrapped().writeTo(sink, last, callback);
        }

        @Override
        public Appendable asAppendable()
        {
            return this;
        }

        @Override
        public boolean isFull()
        {
            return getWrapped().asAppendable().isFull();
        }

        @Override
        public long space()
        {
            return getWrapped().asAppendable().space();
        }

        @Override
        public boolean append(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            return getWrapped().asAppendable().append(bytes);
        }

        @Override
        public boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            return getWrapped().asAppendable().append(bytes);
        }
    }

    /**
     * An abstract implementation of {@link RetainableByteBuffer} that provides the basic {@link Retainable} functionality
     */
    abstract class Abstract implements RetainableByteBuffer.Appendable
    {
        private final Retainable _retainable;

        public Abstract()
        {
            this(new ReferenceCounter());
        }

        public Abstract(Retainable retainable)
        {
            _retainable = Objects.requireNonNull(retainable);
        }

        protected Retainable getRetainable()
        {
            return _retainable;
        }

        @Override
        public boolean canRetain()
        {
            return _retainable.canRetain();
        }

        @Override
        public void retain()
        {
            _retainable.retain();
        }

        @Override
        public boolean release()
        {
            return _retainable.release();
        }

        @Override
        public boolean isRetained()
        {
            return _retainable.isRetained();
        }

        /**
         * Convert Buffer to a detail debug string of pointers and content
         *
         * @return A string showing the pointers and content of the buffer
         */
        public String toString()
        {
            StringBuilder buf = new StringBuilder();

            buf.append(getClass().getSimpleName());
            buf.append("@");
            buf.append(Integer.toHexString(System.identityHashCode(this)));
            buf.append("[");
            buf.append(size());
            buf.append("/");
            if (maxSize() >= Integer.MAX_VALUE)
                buf.append("-");
            else
                buf.append(maxSize());
            addDetailString(buf);
            buf.append(",");
            buf.append(getRetainable());
            buf.append("]");
            addValueString(buf);
            return buf.toString();
        }

        protected void addDetailString(StringBuilder stringBuilder)
        {
        }

        protected void addValueString(StringBuilder stringBuilder)
        {
            if (canRetain())
            {
                stringBuilder.append("={");
                addValueString(stringBuilder, this);
                stringBuilder.append("}");
            }
        }

        protected void addValueString(StringBuilder buf, RetainableByteBuffer value)
        {
            RetainableByteBuffer slice = value.slice();
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
                buf.append(x);
            }
            finally
            {
                slice.release();
            }
        }
    }

    /**
     * A fixed capacity {@link Appendable} {@link RetainableByteBuffer} backed by a single
     * {@link ByteBuffer}.
     */
    class FixedCapacity extends Abstract implements Appendable
    {
        private final ByteBuffer _byteBuffer;
        private int _flipPosition = -1;

        public FixedCapacity(ByteBuffer byteBuffer)
        {
            this(byteBuffer, new ReferenceCounter());
        }

        protected FixedCapacity(ByteBuffer byteBuffer, Retainable retainable)
        {
            super(retainable);
            _byteBuffer = Objects.requireNonNull(byteBuffer);
        }

        @Override
        public Appendable asAppendable()
        {
            if (_byteBuffer.isReadOnly())
                throw new ReadOnlyBufferException();
            return this;
        }

        @Override
        public int remaining()
        {
            if (_flipPosition < 0)
                return super.remaining();
            return _byteBuffer.position() - _flipPosition;
        }

        @Override
        public boolean hasRemaining()
        {
            if (_flipPosition < 0)
                return super.hasRemaining();

            return _flipPosition > 0 || _byteBuffer.position() > 0;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            if (_flipPosition >= 0)
            {
                BufferUtil.flipToFlush(_byteBuffer, _flipPosition);
                _flipPosition = -1;
            }
            return _byteBuffer;
        }

        @Override
        public boolean append(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            ByteBuffer byteBuffer = getByteBuffer();
            if (byteBuffer.isReadOnly() || isRetained())
                throw new ReadOnlyBufferException();
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(byteBuffer);
            BufferUtil.put(bytes, byteBuffer);
            return !bytes.hasRemaining();
        }
    }

    /**
     * An {@link Appendable} {@link RetainableByteBuffer} that can grow its capacity, backed by a chain of {@link ByteBuffer},
     * which may grow either by aggregation and/or retention.
     * When retaining, a chain of zero copy buffers are kept.
     * When aggregating, this class avoid repetitive copies of the same data during growth by aggregating
     * to a chain of buffers, which are only copied to a single buffer if required.
     */
    class DynamicCapacity extends Abstract implements Appendable
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxSize;
        private final List<RetainableByteBuffer> _buffers;
        private final int _aggregationSize;
        private final int _minRetainSize;
        private Appendable _aggregate;

        /**
         * A buffer with no size limit and default aggregation and retention settings.
         */
        public DynamicCapacity()
        {
            this(null, false, -1, -1, -1);
        }

        /**
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         */
        public DynamicCapacity(ByteBufferPool pool, boolean direct, long maxSize)
        {
            this(pool, direct, maxSize, -1, -1);
        }

        /**
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         * @param aggregationSize The default size of aggregation buffers; or 0 for no aggregation growth; or -1 for a default size.
         *                        If the {@code aggregationSize} is 0 and the {@code maxSize} is less that {@link Integer#MAX_VALUE},
         *                        then a single aggregation buffer may be allocated and the class will behave similarly to {@link FixedCapacity}.
         */
        public DynamicCapacity(ByteBufferPool pool, boolean direct, long maxSize, int aggregationSize)
        {
            this(pool, direct, maxSize, aggregationSize, -1);
        }

        /**
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         * @param aggregationSize The default size of aggregation buffers; or 0 for no aggregation growth; or -1 for a default size.
         *                        If the {@code aggregationSize} is 0 and the {@code maxSize} is less that {@link Integer#MAX_VALUE},
         *                        then a single aggregation buffer may be allocated and the class will behave similarly to {@link FixedCapacity}.
         * @param minRetainSize The minimal size of a {@link RetainableByteBuffer} before it will be retained; or 0 to always retain; or -1 for a heuristic;
         */
        public DynamicCapacity(ByteBufferPool pool, boolean direct, long maxSize, int aggregationSize, int minRetainSize)
        {
            this(new ArrayList<>(), pool, direct, maxSize, aggregationSize, minRetainSize);
        }

        private DynamicCapacity(List<RetainableByteBuffer> buffers, ByteBufferPool pool, boolean direct, long maxSize, int aggregationSize, int minRetainSize)
        {
            super();
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
            _minRetainSize = minRetainSize;

            if (_aggregationSize == 0 && _maxSize >= Integer.MAX_VALUE && _minRetainSize != 0)
                throw new IllegalArgumentException("must always retain if cannot aggregate");
        }

        @Override
        public Appendable asAppendable()
        {
            return this;
        }

        @Override
        public ByteBuffer getByteBuffer() throws BufferOverflowException
        {
            return switch (_buffers.size())
            {
                case 0 -> RetainableByteBuffer.EMPTY.getByteBuffer();
                case 1 -> _buffers.get(0).getByteBuffer();
                default ->
                {
                    long size = size();
                    if (size > Integer.MAX_VALUE)
                        throw new BufferOverflowException();

                    int length = (int)size;
                    RetainableByteBuffer combined = _pool.acquire(length, _direct);
                    ByteBuffer byteBuffer = combined.getByteBuffer();
                    BufferUtil.flipToFill(byteBuffer);
                    for (RetainableByteBuffer buffer : _buffers)
                    {
                        byteBuffer.put(buffer.getByteBuffer().slice());
                        buffer.release();
                    }
                    BufferUtil.flipToFlush(byteBuffer, 0);
                    _buffers.clear();
                    _buffers.add(combined);
                    _aggregate = null;
                    yield combined.getByteBuffer();
                }
            };
        }

        /**
         * Take the contents of this buffer, leaving it clear and independent
         * @return An independent buffer with the contents of this buffer, avoiding copies if possible.
         */
        public RetainableByteBuffer takeRetainableByteBuffer()
        {
            return switch (_buffers.size())
            {
                case 0 -> RetainableByteBuffer.EMPTY;
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    _aggregate = null;
                    _buffers.clear();
                    yield buffer;
                }
                default ->
                {
                    List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers);
                    _aggregate = null;
                    _buffers.clear();

                    yield new DynamicCapacity(buffers, _pool, _direct, _maxSize, _aggregationSize, _minRetainSize);
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
        public RetainableByteBuffer.Appendable slice()
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.slice());
            return newSlice(buffers);
        }

        @Override
        public RetainableByteBuffer.Appendable slice(long length)
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (Iterator<RetainableByteBuffer> i = _buffers.iterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                long size = buffer.size();

                // If length is exceeded or this is the last buffer
                if (size > length || !i.hasNext())
                {
                    // slice with length
                    buffers.add(buffer.slice(length));
                    break;
                }

                buffers.add(buffer.slice());
                length -= size;
            }
            return newSlice(buffers);
        }

        private RetainableByteBuffer.Appendable newSlice(List<RetainableByteBuffer> buffers)
        {
            retain();
            Appendable parent = this;
            return new DynamicCapacity(buffers, _pool, _direct, _maxSize, _aggregationSize, _minRetainSize)
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
        public long space()
        {
            long space = maxSize() - size();
            if (space > Integer.MAX_VALUE)
                return Integer.MAX_VALUE;
            return space;
        }

        @Override
        public boolean isFull()
        {
            return size() >= maxSize();
        }

        @Override
        public RetainableByteBuffer copy()
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.copy());

            return new DynamicCapacity(buffers, _pool, _direct, _maxSize, _aggregationSize, _minRetainSize);
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the length of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}
         */
        @Override
        public int remaining()
        {
            long size = size();
            return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(size);
        }

        @Override
        public long size()
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
            long maxSize = maxSize();
            return maxSize > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(maxSize);
        }

        @Override
        public long maxSize()
        {
            return _maxSize;
        }

        @Override
        public boolean release()
        {
            if (super.release())
            {
                for (RetainableByteBuffer buffer : _buffers)
                    buffer.release();
                _buffers.clear();
                _aggregate = null;
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            if (_buffers.isEmpty())
                return;
            _aggregate = null;
            boolean first = true;
            for (Iterator<RetainableByteBuffer> i = _buffers.iterator(); i.hasNext();)
            {
                RetainableByteBuffer rbb = i.next();
                if (first)
                {
                    rbb.clear();
                    first = false;
                }
                else
                {
                    rbb.release();
                    i.remove();
                }
            }
        }

        @Override
        public boolean append(ByteBuffer bytes)
        {
            // Cannot mutate contents if retained
            if (isRetained())
                throw new ReadOnlyBufferException();

            // handle empty appends
            if (bytes == null)
                return true;
            int length = bytes.remaining();
            if (length == 0)
                return true;

            // Try appending to any existing aggregation buffer
            boolean existing = _aggregate != null;
            if (existing)
            {
                if (BufferUtil.append(_aggregate.getByteBuffer(), bytes) == length)
                    return true;

                // we were limited by the capacity of the buffer, fall through to trying to allocate another
                _aggregate = null;
            }

            // are we full?
            long size = size();
            long space = _maxSize - size;
            if (space <= 0)
                return false;

            // Can we use the last buffer as aggregate
            if (!existing && !_buffers.isEmpty())
            {
                RetainableByteBuffer buffer = _buffers.get(_buffers.size() - 1);
                if (buffer instanceof Appendable appendable && appendable.space() >= length && !appendable.isRetained())
                    _aggregate = appendable;
            }

            // acquire a new aggregate buffer if necessary
            if (_aggregate == null)
            {
                int aggregateSize = _aggregationSize;

                // If we cannot grow, allow a single allocation only if we have not already retained.
                if (aggregateSize == 0 && _buffers.isEmpty() && _maxSize < Integer.MAX_VALUE)
                    aggregateSize = (int)_maxSize;
                _aggregate = _pool.acquire(Math.max(length, aggregateSize), _direct).asAppendable();
            }

            // If we were given a buffer larger than the space available, then adjust the capacity
            if (_aggregate.capacity() > space)
            {
                ByteBuffer byteBuffer = _aggregate.getByteBuffer();
                int limit = byteBuffer.limit();
                byteBuffer.limit(limit + Math.toIntExact(space));
                byteBuffer = byteBuffer.slice();
                byteBuffer.limit(limit);
                _aggregate = RetainableByteBuffer.wrap(byteBuffer, _aggregate).asAppendable();
            }

            _buffers.add(_aggregate);

            return _aggregate.append(bytes);
        }

        @Override
        public boolean append(RetainableByteBuffer retainableBytes)
        {
            // Cannot mutate contents if retained
            if (isRetained())
                throw new ReadOnlyBufferException();

            // handle empty appends
            if (retainableBytes == null)
                return true;
            long length = retainableBytes.remaining();
            if (length == 0)
                return true;

            // If we are already aggregating, and the content will fit, then just aggregate
            if (_aggregate != null && _aggregate.space() >= length)
                return _aggregate.append(retainableBytes.getByteBuffer());

            // If the content is a tiny part of the retainable, then better to aggregate rather than accumulate
            if (_minRetainSize != 0)
            {
                // default heuristic is either a fixed size for unknown buffer types or fraction of the capacity for fixed buffers
                int minRetainSize = _minRetainSize > 0 ? _minRetainSize
                    : retainableBytes instanceof FixedCapacity fixed ? fixed.capacity() / 64 : 128;
                if (length < minRetainSize)
                    return append(retainableBytes.getByteBuffer());
            }
            // We will accumulate, so stop any further aggregation without allocating a new aggregate buffer;
            _aggregate = null;

            // Do we have space?
            long space = _maxSize - size();
            if (length <= space)
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
        public boolean appendTo(ByteBuffer to)
        {
            _aggregate = null;
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
            _aggregate = null;
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
        public void putTo(ByteBuffer toInfillMode)
        {
            _aggregate = null;
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                buffer.putTo(toInfillMode);
                buffer.release();
                i.remove();
            }
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            _aggregate = null;
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
        protected void addDetailString(StringBuilder stringBuilder)
        {
            super.addDetailString(stringBuilder);
            stringBuilder.append(",as=");
            stringBuilder.append(_aggregationSize);
            stringBuilder.append(",mr=");
            stringBuilder.append(_minRetainSize);
        }

        @Override
        protected void addValueString(StringBuilder stringBuilder)
        {
            if (canRetain())
            {
                stringBuilder.append("={");
                for (RetainableByteBuffer buffer : _buffers)
                    addValueString(stringBuilder, buffer);
                stringBuilder.append("}");
            }
        }
    }
}
