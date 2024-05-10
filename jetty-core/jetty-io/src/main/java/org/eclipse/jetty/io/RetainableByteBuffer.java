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
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.Blocker;
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
 * <p>The API read-only, even if the underlying {@link ByteBuffer} is read-write.  The {@link Mutable} sub-interface
 * provides a read-write API.  All provided implementation implement {@link Mutable}, but may only present as
 * a {@code RetainableByteBuffer}.  The {@link #asMutable()} method can be used to access the read-write version of the
 * API.</p>
 */
public interface RetainableByteBuffer extends Retainable
{
    /**
     * A Zero-capacity, non-retainable {@code RetainableByteBuffer}.
     */
    RetainableByteBuffer EMPTY = new NonRetainableByteBuffer(BufferUtil.EMPTY_BUFFER);

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
     * @return a {@link NonPooled} buffer wrapping the passed {@link ByteBuffer}
     * @see NonPooled
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer.NonPooled wrap(ByteBuffer byteBuffer)
    {
        return new NonPooled(byteBuffer);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Retainable}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param retainable the associated {@link Retainable}.
     * @return a {@link NonPooled} buffer wrapping the passed {@link ByteBuffer}
     * @see NonPooled
     * @see ByteBufferPool.NonPooling
     */
    static RetainableByteBuffer.NonPooled wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new NonPooled(byteBuffer, retainable);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Runnable} releaser.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param releaser a {@link Runnable} to call when the buffer is released.
     * @return a {@link NonPooled} buffer wrapping the passed {@link ByteBuffer}
     */
    static RetainableByteBuffer.NonPooled wrap(ByteBuffer byteBuffer, Runnable releaser)
    {
        return new NonPooled(byteBuffer)
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
     * @return An {@link Mutable} representation of this buffer with same data and pointers.
     * @throws ReadOnlyBufferException If the buffer is not {@link Mutable} or the backing {@link ByteBuffer} is
     * {@link ByteBuffer#isReadOnly() read-only}.
     */
    default Mutable asMutable() throws ReadOnlyBufferException
    {
        if (isRetained())
            throw new ReadOnlyBufferException();
        if (this instanceof Mutable mutable)
            return mutable;
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
     * Returns a byte from this RetainableByteBuffer at a specific index
     *
     * @param index The index relative to the current start of unconsumed data in the buffer.
     * @return the byte
     * @throws IndexOutOfBoundsException if the index is too large.
     */
    default byte get(long index) throws IndexOutOfBoundsException
    {
        ByteBuffer buffer = getByteBuffer();
        return buffer.get(buffer.position() + Math.toIntExact(index));
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
     * <p>Limit the buffer size to the given number of bytes.</p>
     *
     * @param limit the maximum number of bytes to skip
     */
    default void limit(long limit)
    {
        ByteBuffer byteBuffer = getByteBuffer();
        limit = Math.min(limit, byteBuffer.remaining());
        byteBuffer.limit(byteBuffer.position() + Math.toIntExact(limit));
    }

    /**
     * Get a slice of the buffer.
     * @return A sliced {@link RetainableByteBuffer} sharing this buffers data and reference count, but
     *         with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice()
    {
        if (!canRetain())
            return new NonRetainableByteBuffer(getByteBuffer().slice());

        retain();
        return RetainableByteBuffer.wrap(getByteBuffer().slice(), this);
    }

    /**
     * Get a partial slice of the buffer.
     * This is equivalent, but more efficient, than a {@link #slice()}.{@link #limit(long)}.
     * @param length The number of bytes to slice, which may contain some byte beyond the limit and less than the capacity
     * @return A sliced {@link RetainableByteBuffer} sharing the first {@code length} bytes of this buffers data and
     * reference count, but with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice(long length)
    {
        int size = remaining();
        ByteBuffer byteBuffer = getByteBuffer();
        int limit = byteBuffer.limit();
        ByteBuffer slice;

        if (length <= size)
        {
            byteBuffer.limit(byteBuffer.position() + Math.toIntExact(length));
            slice = byteBuffer.slice();
            byteBuffer.limit(limit);
        }
        else
        {
            length = Math.min(length, byteBuffer.capacity() - byteBuffer.position());
            byteBuffer.limit(byteBuffer.position() + Math.toIntExact(length));
            slice = byteBuffer.slice();
            byteBuffer.limit(limit);
            slice.limit(size);
        }

        if (!canRetain())
            return new NonRetainableByteBuffer(slice);

        retain();
        return RetainableByteBuffer.wrap(slice, this);
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
     * Asynchronously writes and consumes the contents of this retainable byte buffer into given sink.
     * @param sink the destination sink.
     * @param last true if this is the last write.
     * @see org.eclipse.jetty.io.Content.Sink#write(boolean, ByteBuffer, Callback)
     */
    default void writeTo(Content.Sink sink, boolean last) throws IOException
    {
        try (Blocker.Callback callback = Blocker.callback())
        {
            sink.write(last, getByteBuffer(), callback);
            callback.block();
        }
    }

    /**
     * Extends the {@link RetainableByteBuffer} API with optimized mutator methods.
     */
    interface Mutable extends RetainableByteBuffer
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
         * Copies the contents of the given byte buffer to the end of this buffer, growing this buffer if
         * necessary and possible.
         * Copies can be avoided by {@link RetainableByteBuffer#wrap(ByteBuffer) wrapping} the buffer and
         * calling {@link #append(RetainableByteBuffer)}
         * @param bytes the byte buffer to copy from, which is consumed.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if this buffer is read only.
         */
        boolean append(ByteBuffer bytes) throws ReadOnlyBufferException;

        /**
         * Retain or copy the contents of the given retainable byte buffer to the end of this buffer,
         * growing this buffer if necessary and possible.
         * The implementation will heuristically decide to retain or copy the contents.
         * @param bytes the retainable byte buffer to copy from, which is consumed.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if this buffer is read only.
         */
        boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException;

        /**
         * Add the passed {@link ByteBuffer} to this buffer, growing this buffer if necessary and possible.
         * The source {@link ByteBuffer} is passed by reference and the caller gives up ownership, so implementations of this
         * method may avoid copies by keeping a reference to the buffer.
         * @param bytes the byte buffer to add, which is passed by reference and is not necessarily consumed by the add.
         * @return true if the bytes were added else false if they were not.
         * @throws ReadOnlyBufferException if this buffer is read only.
         */
        boolean add(ByteBuffer bytes) throws ReadOnlyBufferException;

        /**
         * Add the passed {@link RetainableByteBuffer} to this buffer, growing this buffer if necessary and possible.
         * The source {@link RetainableByteBuffer} is passed by reference and the caller gives up ownership, so implementations
         * of this method may avoid copies by keeping a reference to the buffer, but they must ultimately
         * {@link RetainableByteBuffer#release()} the buffer.
         * @param bytes the byte buffer to add, which is passed by reference and is not necessarily consumed by the add.
         * @return true if the bytes were added else false if they were not.
         * @throws ReadOnlyBufferException if this buffer is read only.
         */
        boolean add(RetainableByteBuffer bytes) throws ReadOnlyBufferException;

        /**
         * Put a {@code byte} to the buffer, growing this buffer if necessary and possible.
         * @param b the {@code byte} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        void put(byte b);

        /**
         * Put a {@code short} to the buffer, growing this buffer if necessary and possible.
         * @param s the {@code short} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        void putShort(short s);

        /**
         * Put an {@code int} to the buffer, growing this buffer if necessary and possible.
         * @param i the {@code int} to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        void putInt(int i);

        /**
         * Put a {@code long} to the buffer, growing this buffer if necessary and possible.
         * @param l the {@code long} to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        void putLong(long l);

        /**
         * Put a {@code byte} array to the buffer, growing this buffer if necessary and possible.
         * @param bytes the {@code byte} array to put
         * @param offset the offset into the array
         * @param length the length in bytes to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        void put(byte[] bytes, int offset, int length);

        /**
         * Put a {@code byte} array to the buffer, growing this buffer if necessary and possible.
         * @param bytes the {@code byte} array to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        default void put(byte[] bytes)
        {
            put(bytes, 0, bytes.length);
        }

        /**
         * Put a {@code byte} to the buffer at a given index.
         * @param index The index relative to the current start of unconsumed data in the buffer.
         * @param b the {@code byte} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        void put(long index, byte b);
    }

    /**
     * A wrapper for {@link RetainableByteBuffer} instances
     */
    class Wrapper extends Retainable.Wrapper implements Mutable
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
        public byte get(long index)
        {
            return getWrapped().get(index);
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
        public Mutable asMutable()
        {
            return this;
        }

        @Override
        public boolean isFull()
        {
            return getWrapped().asMutable().isFull();
        }

        @Override
        public long space()
        {
            return getWrapped().asMutable().space();
        }

        @Override
        public boolean append(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            return getWrapped().asMutable().append(bytes);
        }

        @Override
        public boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            return getWrapped().asMutable().append(bytes);
        }

        @Override
        public boolean add(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            return getWrapped().asMutable().add(bytes);
        }

        @Override
        public boolean add(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            return getWrapped().asMutable().add(bytes);
        }

        @Override
        public void put(byte b)
        {
            getWrapped().asMutable().put(b);
        }

        @Override
        public void put(long index, byte b)
        {
            getWrapped().asMutable().put(index, b);
        }

        @Override
        public void putShort(short s)
        {
            getWrapped().asMutable().putShort(s);
        }

        @Override
        public void putInt(int i)
        {
            getWrapped().asMutable().putInt(i);
        }

        @Override
        public void putLong(long l)
        {
            getWrapped().asMutable().putLong(l);
        }

        @Override
        public void put(byte[] bytes, int offset, int length)
        {
            getWrapped().asMutable().put(bytes, offset, length);
        }
    }

    /**
     * An abstract implementation of {@link RetainableByteBuffer} that provides the basic {@link Retainable} functionality
     */
    abstract class Abstract implements Mutable
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
            if (value instanceof FixedCapacity)
            {
                BufferUtil.appendDebugString(buf, value.getByteBuffer());
            }
            else if (value.canRetain())
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
            else
            {
                buf.append("<unknown>");
            }
        }
    }

    /**
     * A fixed capacity {@link Mutable} {@link RetainableByteBuffer} backed by a single
     * {@link ByteBuffer}.
     */
    class FixedCapacity extends Abstract implements Mutable
    {
        private final ByteBuffer _byteBuffer;
        /*
         * Remember the flip mode of the internal bytebuffer.  This is useful when a FixedCapacity buffer is used
         * to aggregate multiple other buffers (e.g. by DynamicCapacity buffer), as it avoids a flip/flop on every append.
         */
        private int _flipPosition = -1;

        public FixedCapacity(ByteBuffer byteBuffer)
        {
            this(byteBuffer, new ReferenceCounter());
        }

        public FixedCapacity(ByteBuffer byteBuffer, Retainable retainable)
        {
            super(retainable);
            _byteBuffer = Objects.requireNonNull(byteBuffer);
        }

        @Override
        public void clear()
        {
            super.clear();
            _byteBuffer.clear();
            _flipPosition = 0;
        }

        @Override
        public Mutable asMutable()
        {
            if (_byteBuffer.isReadOnly() || isRetained())
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
            // Ensure buffer is in flush mode if accessed externally
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
            if (add(bytes))
                return true;

            int space = _byteBuffer.remaining();
            int position = _byteBuffer.position();
            _byteBuffer.put(position, bytes, 0, space);
            _byteBuffer.position(position + space);
            bytes.position(bytes.position() + space);
            return false;
        }

        @Override
        public boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            assert !isRetained();
            return bytes.remaining() == 0 || append(bytes.getByteBuffer());
        }

        @Override
        public boolean add(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            int space = _byteBuffer.remaining();
            int length = bytes.remaining();

            if (length <= space)
            {
                _byteBuffer.put(bytes);
                return true;
            }

            return false;
        }

        @Override
        public boolean add(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            assert !isRetained();
            if (add(bytes.getByteBuffer()))
            {
                bytes.release();
                return true;
            }
            return false;
        }

        /**
         * Put a {@code byte} to the buffer, growing this buffer if necessary and possible.
         * @param b the {@code byte} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public void put(byte b)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.put(b);
        }

        @Override
        public void put(long index, byte b)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);
            int remaining = _byteBuffer.position() - _flipPosition;
            if (index > remaining)
                throw new IndexOutOfBoundsException();
            _byteBuffer.put(_flipPosition + Math.toIntExact(index), b);
        }

        /**
         * Put a {@code short} to the buffer, growing this buffer if necessary and possible.
         * @param s the {@code short} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public void putShort(short s)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.putShort(s);
        }

        /**
         * Put an {@code int} to the buffer, growing this buffer if necessary and possible.
         * @param i the {@code int} to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public void putInt(int i)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.putInt(i);
        }

        /**
         * Put a {@code long} to the buffer, growing this buffer if necessary and possible.
         * @param l the {@code long} to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public void putLong(long l)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.putLong(l);
        }

        /**
         * Put a {@code byte} array to the buffer, growing this buffer if necessary and possible.
         * @param bytes the {@code byte} array to put
         * @param offset the offset into the array
         * @param length the length in bytes to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public void put(byte[] bytes, int offset, int length)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.put(bytes, offset, length);
        }
    }

    /**
     * A {@link FixedCapacity} buffer that is not pooled, but may be {@link Retainable#canRetain() retained}.
     * A {@code NonPooled} buffer, that is not {@link #isRetained() retained} can have its internal buffers taken
     * without retention (e.g. {@link DynamicCapacity#takeByteArray()}).  The {@code wrap} methods return {@code NonPooled}
     * buffers.
     * @see #wrap(ByteBuffer)
     * @see #wrap(ByteBuffer, Runnable)
     * @see #wrap(ByteBuffer, Retainable)
     */
    class NonPooled extends FixedCapacity
    {
        public NonPooled(ByteBuffer byteBuffer)
        {
            super(byteBuffer);
        }

        protected NonPooled(ByteBuffer byteBuffer, Retainable retainable)
        {
            super(byteBuffer, retainable);
        }
    }

    /**
     * a {@link FixedCapacity} buffer that is neither not pooled nor {@link Retainable#canRetain() retainable}.
     */
    class NonRetainableByteBuffer extends NonPooled
    {
        public NonRetainableByteBuffer(ByteBuffer byteBuffer)
        {
            super(byteBuffer, NON_RETAINABLE);
        }

        protected void addValueString(StringBuilder stringBuilder)
        {
            stringBuilder.append("={");
            addValueString(stringBuilder, this);
            stringBuilder.append("}");
        }
    }

    /**
     * An {@link Mutable} {@link RetainableByteBuffer} that can grow its capacity, backed by a chain of {@link ByteBuffer},
     * which may grow either by aggregation and/or retention.
     * When retaining, a chain of zero copy buffers are kept.
     * When aggregating, this class avoid repetitive copies of the same data during growth by aggregating
     * to a chain of buffers, which are only copied to a single buffer if required.
     * If the {@code minRetainSize} is {code 0}, then appending to this buffer will always retain and accumulate.
     * If the {@code minRetainSize} is {@link Integer#MAX_VALUE}, then appending to this buffer will always aggregate.
     */
    class DynamicCapacity extends Abstract implements Mutable
    {
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxSize;
        private final List<RetainableByteBuffer> _buffers;
        private final int _aggregationSize;
        private final int _minRetainSize;
        private Mutable _aggregate;

        /**
         * A buffer with no size limit and default aggregation and retention settings.
         */
        public DynamicCapacity()
        {
            this(null, false, -1, -1, -1);
        }

        /**
         * @param pool The pool from which to allocate buffers
         */
        public DynamicCapacity(ByteBufferPool pool)
        {
            this(pool, false, -1, -1, -1);
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
        public Mutable asMutable()
        {
            if (isRetained())
                throw new ReadOnlyBufferException();
            return this;
        }

        @Override
        public ByteBuffer getByteBuffer() throws BufferOverflowException
        {
            return switch (_buffers.size())
            {
                case 0 -> BufferUtil.EMPTY_BUFFER;
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

        /**
         * Take the contents of this buffer, leaving it clear and independent
         * @return A possibly newly allocated array with the contents of this buffer, avoiding copies if possible.
         * The length of the array may be larger than the contents, but the offset will always be 0.
         */
        public byte[] takeByteArray()
        {
            return switch (_buffers.size())
            {
                case 0 -> BufferUtil.EMPTY_BUFFER.array();
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    _aggregate = null;
                    _buffers.clear();

                    // The array within the buffer can be used if it is not pooled, is not shared and it exits
                    byte[] array = (buffer instanceof NonPooled && !buffer.isRetained() && !buffer.isDirect())
                        ? buffer.getByteBuffer().array() : BufferUtil.toArray(buffer.getByteBuffer());

                    buffer.release();
                    yield array;
                }
                default ->
                {
                    long size = size();
                    if (size > Integer.MAX_VALUE)
                        throw new BufferOverflowException();

                    int length = (int)size;
                    byte[] array = new byte[length];

                    int offset = 0;
                    for (RetainableByteBuffer buffer : _buffers)
                    {
                        int remaining = buffer.remaining();
                        buffer.get(array, offset, remaining);
                        offset += remaining;
                        buffer.release();
                    }
                    _buffers.clear();
                    _aggregate = null;
                    yield array;
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
        public byte get(long index) throws IndexOutOfBoundsException
        {
            for (RetainableByteBuffer buffer : _buffers)
            {
                long size = buffer.size();
                if (index < size)
                    return buffer.get(Math.toIntExact(index));
                index -= size;
            }
            throw new IndexOutOfBoundsException();
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
        public void limit(long limit)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.iterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();

                long size = buffer.size();
                if (limit == 0)
                {
                    buffer.release();
                    i.remove();
                }
                else if (limit < size)
                {
                    buffer.asMutable().limit(limit);
                    limit = 0;
                }
                else
                {
                    limit -= size;
                }
            }
        }

        @Override
        public Mutable slice()
        {
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.slice());
            return newSlice(buffers);
        }

        @Override
        public Mutable slice(long length)
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

        private Mutable newSlice(List<RetainableByteBuffer> buffers)
        {
            retain();
            Mutable parent = this;
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
            for (Iterator<RetainableByteBuffer> i = _buffers.iterator(); i.hasNext();)
            {
                RetainableByteBuffer rbb = i.next();
                if (rbb == _aggregate)
                {
                    // We were aggregating so let's keep one buffer to aggregate again.
                    rbb.clear();
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
            assert !isRetained();

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

            // We will aggregate, either into the last buffer or a newly allocated one.
            if (!existing &&
                !_buffers.isEmpty() &&
                _buffers.get(_buffers.size() - 1) instanceof Mutable mutable &&
                mutable.space() >= length &&
                !mutable.isRetained())
            {
                // We can use the last buffer as the aggregate
                _aggregate = mutable;
                checkAggregateLimit(space);
            }
            else
            {
                // acquire a new aggregate buffer
                int aggregateSize = _aggregationSize;

                // If we cannot grow, allow a single allocation only if we have not already retained.
                if (aggregateSize == 0 && _buffers.isEmpty() && _maxSize < Integer.MAX_VALUE)
                    aggregateSize = (int)_maxSize;
                _aggregate = _pool.acquire(Math.max(length, aggregateSize), _direct).asMutable();
                checkAggregateLimit(space);
                _buffers.add(_aggregate);
            }

            return _aggregate.append(bytes);
        }

        private void checkAggregateLimit(long space)
        {
            // If the new aggregate buffer is larger than the space available, then adjust the capacity
            if (_aggregate.capacity() > space)
            {
                ByteBuffer byteBuffer = _aggregate.getByteBuffer();
                int limit = byteBuffer.limit();
                byteBuffer.limit(limit + Math.toIntExact(space));
                byteBuffer = byteBuffer.slice();
                byteBuffer.limit(limit);
                _aggregate = RetainableByteBuffer.wrap(byteBuffer, _aggregate).asMutable();
            }
        }

        @Override
        public boolean append(RetainableByteBuffer retainableBytes)
        {
            // Cannot mutate contents if retained
            assert !isRetained();

            // handle empty appends
            if (retainableBytes == null)
                return true;
            long length = retainableBytes.remaining();
            if (length == 0)
                return true;

            // If we are already aggregating, and the content will fit, and the pass buffer is mostly empty then just aggregate
            if (_aggregate != null && _aggregate.space() >= length && (length * 100) < retainableBytes.maxSize())
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
        public boolean add(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            return add(RetainableByteBuffer.wrap(bytes));
        }

        @Override
        public boolean add(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            long size = size();
            long space = _maxSize - size;
            long length = bytes.size();
            if (space < length)
                return false;

            if (_aggregate != null && length < _minRetainSize && append(bytes))
            {
                bytes.release();
                return true;
            }

            _buffers.add(bytes);
            _aggregate = null;
            return true;
        }

        @Override
        public void put(byte b)
        {
            ensure(1).put(b);
        }

        @Override
        public void put(long index, byte b)
        {
            for (RetainableByteBuffer buffer : _buffers)
            {
                long size = buffer.size();
                if (index < size)
                {
                    buffer.asMutable().put(index, b);
                    return;
                }
                index -= size;
            }
            throw new IndexOutOfBoundsException();
        }

        @Override
        public void putShort(short s)
        {
            ensure(2).putShort(s);
        }

        @Override
        public void putInt(int i)
        {
            ensure(4).putInt(i);
        }

        @Override
        public void putLong(long l)
        {
            ensure(8).putLong(l);
        }

        @Override
        public void put(byte[] bytes, int offset, int length)
        {
            // TODO perhaps split if there is an existing aggregate buffer?
            ensure(length).put(bytes, offset, length);
        }

        private Mutable ensure(int needed) throws BufferOverflowException
        {
            long size = size();
            long space = _maxSize - size;
            if (space < needed)
                throw new BufferOverflowException();
            if (_aggregate != null)
            {
                if (_aggregate.space() >= needed)
                    return _aggregate;
            }
            else if (!_buffers.isEmpty() &&
                _buffers.get(_buffers.size() - 1) instanceof Mutable mutable &&
                mutable.space() >= needed &&
                !mutable.isRetained())
            {
                _aggregate = mutable;
                return _aggregate;
            }

            // We need a new aggregate, acquire a new aggregate buffer
            int aggregateSize = _aggregationSize;

            // If we cannot grow, allow a single allocation only if we have not already retained.
            if (aggregateSize == 0 && _buffers.isEmpty() && _maxSize < Integer.MAX_VALUE)
                aggregateSize = (int)_maxSize;
            _aggregate = _pool.acquire(Math.max(needed, aggregateSize), _direct).asMutable();

            // If the new aggregate buffer is larger than the space available, then adjust the capacity
            checkAggregateLimit(space);
            _buffers.add(_aggregate);
            return _aggregate;
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
                default ->
                {
                    // Can we do a gather write?
                    if (!last && sink instanceof EndPoint endPoint)
                    {
                        ByteBuffer[] buffers = new ByteBuffer[_buffers.size()];
                        int i = 0;
                        for (RetainableByteBuffer rbb : _buffers)
                            buffers[i++] = rbb.getByteBuffer();
                        endPoint.write(callback, buffers);
                        return;
                    }

                    // write buffer by buffer
                    new IteratingNestedCallback(callback)
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
        }

        @Override
        protected void addDetailString(StringBuilder stringBuilder)
        {
            super.addDetailString(stringBuilder);
            stringBuilder.append(",aggSize=");
            stringBuilder.append(_aggregationSize);
            stringBuilder.append(",minRetain=");
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
