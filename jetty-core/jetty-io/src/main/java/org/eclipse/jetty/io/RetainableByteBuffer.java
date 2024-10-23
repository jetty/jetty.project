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
import java.util.ListIterator;
import java.util.Objects;

import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An abstraction over {@link ByteBuffer}s which provides:</p>
 * <ul>
 *     <li>{@link Retainable Retainability} so that reference counts can be maintained for shared buffers.</li>
 *     <li>{@link Pooled Pooled} buffers that use the {@link ByteBufferPool} for any operations and which are returned to the
 *         {@link ByteBufferPool} when fully {@link #release() released}.</li>
 *     <li>Either {@link FixedCapacity fixed capacity} buffers over a single {@link ByteBuffer} or
 *         {@link DynamicCapacity dynamic capacity} over possible multiple {@link ByteBuffer}s.</li>
 *     <li>Access APIs to {@link #get() get}, {@link #slice() slice} or {@link #take() take} from
 *         the buffer</li>
 *     <li>A {@link Mutable Mutable} API variant to {@link Mutable#put(byte) put},
 *         {@link Mutable#append(RetainableByteBuffer) append} or {@link Mutable#add(RetainableByteBuffer) add}
 *         to the buffer</li>
 * </ul>
 * <p>When possible and optimal, implementations will avoid data copies. However, copies may be favoured over retaining
 * large buffers with small content.
 * </p>
 * <p>Accessing data in the buffer can be achieved via:</p>
 * <ul>
 *     <li>The {@link #get()}/{@link #get(long)}/{@link #get(byte[], int, int)} methods provide direct access to bytes
 *         within the buffer.</li>
 *     <li>The {@link #slice()}/{@link #slice(long)} methods for shared access to common backing buffers, but with
 *         independent indexes.</li>
 *     <li>The {@link #take()}/{@link #take(long)} methods for minimal copy extraction of bulk data.</li>
 *     <li>Accessing the underlying {@link ByteBuffer} via {@link #getByteBuffer()}, which may coalesce multiple buffers
 *     into a single.</li>
 * </ul>
 * <p>The {@code RetainableByteBuffer} APIs are non-modal, meaning that there is no need for any {@link ByteBuffer#flip() flip}
 * operation between a mutable method and an accessor method.
 * {@link ByteBuffer} returned or passed to this API should be in "flush" mode, with valid data between the
 * {@link ByteBuffer#position() position} and {@link ByteBuffer#limit() limit}.  The {@link ByteBuffer} returned from
 * {@link #getByteBuffer()} may used directly and switched to "fill" mode, but it is the callers responsibility to
 * {@link ByteBuffer#flip() flip} back to "flush" mode, before any {@code RetainableByteBuffer} APIs are used.</p>
 * <p>The {@code RetainableByteBuffer} APIs hide any notion of unused space before or after valid data. All indexing is relative
 * to the first byte of data in the buffer and no manipulation of data pointers is directly supported.</p>
 * <p>The buffer may be large and the {@link #size()} is represented as a {@code long} in new APIs.  However, APIs that
 * are tied to a single backing {@link ByteBuffer} may use integer representations of size and indexes.</p>
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
     * @return a {@link FixedCapacity} buffer wrapping the passed {@link ByteBuffer}
     * @see ByteBufferPool#NON_POOLING
     */
    static RetainableByteBuffer.Mutable wrap(ByteBuffer byteBuffer)
    {
        return new FixedCapacity(byteBuffer);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Retainable}.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param retainable the associated {@link Retainable}.
     * @return a {@link FixedCapacity} buffer wrapping the passed {@link ByteBuffer}
     * @see ByteBufferPool#NON_POOLING
     */
    static RetainableByteBuffer.Mutable wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new FixedCapacity(byteBuffer, retainable);
    }

    /**
     * <p>Returns a {@code RetainableByteBuffer} that wraps
     * the given {@code ByteBuffer} and {@link Runnable} releaser.</p>
     *
     * @param byteBuffer the {@code ByteBuffer} to wrap
     * @param releaser a {@link Runnable} to call when the buffer is released.
     * @return a {@link FixedCapacity} buffer wrapping the passed {@link ByteBuffer}
     */
    static RetainableByteBuffer.Mutable wrap(ByteBuffer byteBuffer, Runnable releaser)
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
     * Check if the underlying implementation is mutable.
     * Note that the immutable {@link RetainableByteBuffer} API may be backed by a mutable {@link ByteBuffer} or
     * the {@link Mutable} API may be backed by an immutable {@link ByteBuffer}.
     * @return whether this buffers implementation is mutable
     * @see #asMutable()
     */
    default boolean isMutable()
    {
        return !getByteBuffer().isReadOnly();
    }

    /**
     * Access this buffer via the {@link Mutable} API.
     * Note that the {@link Mutable} API may be backed by an immutable {@link ByteBuffer}.
     * @return An {@link Mutable} representation of this buffer with same data and pointers.
     * @throws ReadOnlyBufferException If the buffer is not {@link Mutable} or the backing {@link ByteBuffer} is
     * {@link ByteBuffer#isReadOnly() read-only}.
     * @see #isMutable()
     */
    default Mutable asMutable() throws ReadOnlyBufferException
    {
        if (!isMutable() || isRetained())
            throw new ReadOnlyBufferException();
        if (this instanceof Mutable mutable)
            return mutable;
        throw new ReadOnlyBufferException();
    }

    /**
     * {@link #release() Releases} the buffer in a way that ensures it will not be recycled in a buffer pool.
     * This method should be used in cases where it is unclear if operations on the buffer have completed
     * (for example, when a write operation has been aborted asynchronously or timed out, but the write
     * operation may still be pending).
     * @return whether if the buffer was released.
     * @see ByteBufferPool#releaseAndRemove(RetainableByteBuffer)
     */
    default boolean releaseAndRemove()
    {
        return release();
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
     * @see #get(byte[], int, int)
     * @see #get(long)
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
     * <p>If the implementation contains multiple buffers, they are coalesced to a single buffer before being returned.
     * If the content is too large for a single {@link ByteBuffer}, then the content should be access with
     * {@link #writeTo(Content.Sink, boolean)}.</p>
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
     * <p>Limit this buffer's contents to the size.</p>
     *
     * @param size the new size of the buffer
     */
    default void limit(long size)
    {
        ByteBuffer byteBuffer = getByteBuffer();
        size = Math.min(size, byteBuffer.remaining());
        byteBuffer.limit(byteBuffer.position() + Math.toIntExact(size));
    }

    /**
     * Get a slice of the buffer.
     * @return A sliced {@link RetainableByteBuffer} sharing this buffers data and reference count, but
     *         with independent position. The buffer is {@link #retain() retained} by this call.
     * @see #slice(long)
     */
    default RetainableByteBuffer slice()
    {
        return slice(Long.MAX_VALUE);
    }

    /**
     * Get a partial slice of the buffer.
     * This is equivalent to {@link #slice()}.{@link #limit(long)}, but may be implemented more efficiently.
     * @param length The number of bytes to slice, which may beyond the limit and less than the capacity, in which case
     * it will ensure some spare capacity in the slice.
     * @return A sliced {@link RetainableByteBuffer} sharing the first {@code length} bytes of this buffers data and
     * reference count, but with independent position. The buffer is {@link #retain() retained} by this call.
     */
    default RetainableByteBuffer slice(long length)
    {
        int size = remaining();
        ByteBuffer byteBuffer = getByteBuffer();
        int limit = byteBuffer.limit();

        byteBuffer.limit(byteBuffer.position() + Math.toIntExact(Math.min(length, size)));
        ByteBuffer slice = byteBuffer.slice();
        byteBuffer.limit(limit);
        if (length > size)
            slice.limit(size);

        if (!canRetain())
            return new NonRetainableByteBuffer(slice);

        retain();
        return RetainableByteBuffer.wrap(slice, this);
    }

    /**
     * Take the contents of this buffer, from the head, leaving remaining bytes in this buffer.
     * This is similar to {@link #slice(long)} followed by a {@link #skip(long)}, but avoids shared data.
     * @param length The number of bytes to take
     * @return A buffer with the contents of this buffer after limiting bytes, avoiding copies if possible,
     * but with no shared internal buffers.
     */
    default RetainableByteBuffer take(long length)
    {
        if (isEmpty() || length == 0)
            return EMPTY;

        RetainableByteBuffer slice = slice(length);
        skip(length);
        if (slice.isRetained())
        {
            RetainableByteBuffer copy = slice.copy();
            slice.release();
            return copy;
        }
        return slice;
    }

    /**
     * Take the contents of this buffer, from the tail, leaving remaining bytes in this buffer.
     * @param skip The number of bytes to skip before taking the tail.
     * @return A buffer with the contents of this buffer after skipping bytes, avoiding copies if possible,
     * but with no shared internal buffers.
     */
    default RetainableByteBuffer takeFrom(long skip)
    {
        if (isEmpty() || skip > size())
            return EMPTY;

        RetainableByteBuffer slice = slice();
        slice.skip(skip);
        limit(skip);
        if (slice.isRetained())
        {
            RetainableByteBuffer copy = slice.copy();
            slice.release();
            return copy;
        }
        return slice;
    }

    /**
     * Take the contents of this buffer, leaving it clear.
     * @return A buffer with the contents of this buffer, avoiding copies if possible.
     * @see #take(long)
     * @see #takeFrom(long)
     */
    default RetainableByteBuffer take()
    {
        return take(Long.MAX_VALUE);
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
     * Asynchronously writes and consumes the contents of this retainable byte buffer into the given sink.
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
     * Writes and consumes the contents of this retainable byte buffer into the given sink.
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
     * @return A string showing the info and detail about this buffer, as well as a summary of the contents
     */
    default String toDetailString()
    {
        return toString();
    }

    /**
     * Extended {@link RetainableByteBuffer} API with mutator methods.
     * The mutator methods come in the following styles:
     * <ul>
     *     <li>{@code put} methods are used for putting raw bytes into the buffer and are
     *     similar to {@link ByteBuffer#put(byte)} etc. {@code Put} methods may be used in fluent style.</li>
     *     <li>{@code add} methods are used for handing over an external buffer to be managed by
     *     this buffer. External buffers are passed by reference and the caller will not longer manage the added buffer.
     *     {@code Add} methods may be used in fluent style.</li>
     *     <li>{@code append} methods are used for handing over the content of a buffer to be included in this buffer.
     *     The caller may still use the passed buffer and is responsible for eventually releasing it.</li>
     * </ul>
     *
     */
    interface Mutable extends RetainableByteBuffer
    {
        /**
         * @return the number of bytes that can be added, appended or put into this buffer.
         */
        default long space()
        {
            return capacity() - remaining();
        }

        /**
         * @return true if the {@link #size()} is equals to the {@link #maxSize()} and no more bytes can be added, appended
         * or put to this buffer.
         */
        default boolean isFull()
        {
            return space() == 0;
        }

        /**
         * Add the passed {@link ByteBuffer} to this buffer, growing this buffer if necessary and possible.
         * The source {@link ByteBuffer} is passed by reference and the caller gives up "ownership", so implementations of
         * this method may choose to avoid copies by keeping a reference to the buffer.
         * @param bytes the byte buffer to add, which is passed by reference and is not necessarily consumed by the add.
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         * @see #append(ByteBuffer)
         */
        Mutable add(ByteBuffer bytes) throws ReadOnlyBufferException, BufferOverflowException;

        /**
         * Add the passed {@link RetainableByteBuffer} to this buffer, growing this buffer if necessary and possible.
         * The source {@link RetainableByteBuffer} is passed by reference and the caller gives up ownership, so
         * implementations of this method may avoid copies by keeping a reference to the buffer.
         * Unlike the similar {@link #append(RetainableByteBuffer)} and contrary to the general rules of {@link Retainable},
         * implementations of this method need not call {@link #retain()} if keeping a reference, but they must ultimately
         * call {@link #release()} the passed buffer.
         * Callers should use {@code add} rather than {@link #append(RetainableByteBuffer)} if they already have an obligation
         * to release the buffer and wish to delegate that obligation to this buffer.
         * @param bytes the byte buffer to add, which is passed by reference and is not necessarily consumed by the add.
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable add(RetainableByteBuffer bytes) throws ReadOnlyBufferException, BufferOverflowException;

        /**
         * Copies the contents of the given byte buffer to the end of this buffer, growing this buffer if
         * necessary and possible.
         * @param bytes the byte buffer to copy from, which is consumed.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @see #add(ByteBuffer)
         */
        boolean append(ByteBuffer bytes) throws ReadOnlyBufferException;

        /**
         * Retain or copy the contents of the given retainable byte buffer to the end of this buffer,
         * growing this buffer if necessary and possible.
         * The implementation will heuristically decide to retain or copy the contents
         * Unlike the similar {@link #add(RetainableByteBuffer)}, implementations of this method must
         * {@link RetainableByteBuffer#retain()} the passed buffer if they keep a reference to it.
         * @param bytes the retainable byte buffer to copy from, which is consumed.
         * @return true if all bytes of the given buffer were copied, false otherwise.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @see #add(RetainableByteBuffer)
         */
        boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException;

        /**
         * Put a {@code byte} to the buffer, growing this buffer if necessary and possible.
         * @param b the {@code byte} to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable put(byte b);

        /**
         * Put a {@code short} to the buffer, growing this buffer if necessary and possible.
         * @param s the {@code short} to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable putShort(short s);

        /**
         * Put an {@code int} to the buffer, growing this buffer if necessary and possible.
         * @param i the {@code int} to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable putInt(int i);

        /**
         * Put a {@code long} to the buffer, growing this buffer if necessary and possible.
         * @param l the {@code long} to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable putLong(long l);

        /**
         * Put a {@code byte} array to the buffer, growing this buffer if necessary and possible.
         * @param bytes the {@code byte} array to put
         * @param offset the offset into the array
         * @param length the length in bytes to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable put(byte[] bytes, int offset, int length);

        /**
         * Put a {@code byte} array to the buffer, growing this buffer if necessary and possible.
         * @param bytes the {@code byte} array to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        default Mutable put(byte[] bytes)
        {
            return put(bytes, 0, bytes.length);
        }

        /**
         * Put a {@code byte} to the buffer at a given index.
         * @param index The index relative to the current start of unconsumed data in the buffer.
         * @param b the {@code byte} to put
         * @return {@code this} buffer.
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        Mutable put(long index, byte b);
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
        public boolean releaseAndRemove()
        {
            return getWrapped().releaseAndRemove();
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
        public RetainableByteBuffer slice(long length)
        {
            return getWrapped().slice(length);
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
        public Mutable add(ByteBuffer bytes) throws ReadOnlyBufferException, BufferOverflowException
        {
            getWrapped().asMutable().add(bytes);
            return this;
        }

        @Override
        public Mutable add(RetainableByteBuffer bytes) throws ReadOnlyBufferException, BufferOverflowException
        {
            getWrapped().asMutable().add(bytes);
            return this;
        }

        @Override
        public Mutable put(byte b)
        {
            getWrapped().asMutable().put(b);
            return this;
        }

        @Override
        public Mutable put(long index, byte b)
        {
            getWrapped().asMutable().put(index, b);
            return this;
        }

        @Override
        public Mutable putShort(short s)
        {
            getWrapped().asMutable().putShort(s);
            return this;
        }

        @Override
        public Mutable putInt(int i)
        {
            getWrapped().asMutable().putInt(i);
            return this;
        }

        @Override
        public Mutable putLong(long l)
        {
            getWrapped().asMutable().putLong(l);
            return this;
        }

        @Override
        public Mutable put(byte[] bytes, int offset, int length)
        {
            getWrapped().asMutable().put(bytes, offset, length);
            return this;
        }

        @Override
        public String toDetailString()
        {
            return getWrapped().toDetailString();
        }
    }

    /**
     * An abstract implementation of {@link RetainableByteBuffer} that provides the basic {@link Retainable} functionality
     */
    abstract class Abstract extends Retainable.Wrapper implements Mutable
    {
        public Abstract()
        {
            this(new ReferenceCounter());
        }

        public Abstract(Retainable retainable)
        {
            super(retainable);
        }

        /**
         * @return A string showing the info about this buffer
         */
        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            addStringInfo(builder);
            return builder.toString();
        }

        /**
         * @return A string showing the info and detail about this buffer
         */
        @Override
        public String toDetailString()
        {
            StringBuilder builder = new StringBuilder();
            addStringInfo(builder);
            builder.append("={");
            addValueString(builder);
            builder.append("}");
            return builder.toString();
        }

        protected void addStringInfo(StringBuilder builder)
        {
            builder.append(getClass().getSimpleName());
            builder.append("@");
            builder.append(Integer.toHexString(System.identityHashCode(this)));
            builder.append("[");
            builder.append(size());
            builder.append("/");
            builder.append(maxSize());
            builder.append(",d=");
            builder.append(isDirect());
            addExtraStringInfo(builder);
            builder.append(",r=");
            builder.append(getRetained());
            builder.append("]");
        }

        protected void addExtraStringInfo(StringBuilder builder)
        {
        }

        protected void addValueString(StringBuilder builder)
        {
            addValueMarker(builder, true);
            long size = size();
            if (size <= 48)
            {
                for (int i = 0; i < size; i++)
                    BufferUtil.appendDebugByte(builder, get(i));
            }
            else
            {
                for (int i = 0; i < 24; i++)
                    BufferUtil.appendDebugByte(builder, get(i));
                builder.append("...");
                for (int i = 0; i < 24; i++)
                    BufferUtil.appendDebugByte(builder, get(size - 24 + i));
            }
            addValueMarker(builder, false);
        }

        protected void addValueMarker(StringBuilder builder, boolean beginning)
        {
            builder.append(beginning ? "<<<" : ">>>");
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
            if (!isMutable() || isRetained())
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
        public boolean isDirect()
        {
            return _byteBuffer.isDirect();
        }

        @Override
        public int capacity()
        {
            return _byteBuffer.capacity();
        }

        @Override
        public byte get(long index) throws IndexOutOfBoundsException
        {
            int offset = _flipPosition < 0 ? _byteBuffer.position() : _flipPosition;
            return _byteBuffer.get(offset + Math.toIntExact(index));
        }

        @Override
        public void limit(long size)
        {
            if (_flipPosition < 0)
                super.limit(size);
            else
                _byteBuffer.position(_flipPosition + Math.toIntExact(Math.min(size, size())));
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
            // Try to add the whole buffer
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            int length = bytes.remaining();
            int space = _byteBuffer.remaining();

            if (space == 0)
                return length == 0;

            if (length > space)
            {
                // No space for the whole buffer, so put as much as we can
                int position = _byteBuffer.position();
                _byteBuffer.put(position, bytes, bytes.position(), space);
                _byteBuffer.position(position + space);
                bytes.position(bytes.position() + space);
                return false;
            }

            if (length > 0)
                _byteBuffer.put(bytes);
            return true;
        }

        @Override
        public boolean append(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            assert !isRetained();
            return bytes.remaining() == 0 || append(bytes.getByteBuffer());
        }

        @Override
        public Mutable add(ByteBuffer bytes) throws ReadOnlyBufferException
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            int length = bytes.remaining();
            int space = _byteBuffer.remaining();

            if (length > space)
                throw new BufferOverflowException();

            if (length > 0)
                _byteBuffer.put(bytes);

            return this;
        }

        @Override
        public Mutable add(RetainableByteBuffer bytes) throws ReadOnlyBufferException
        {
            assert !isRetained();

            if (bytes instanceof DynamicCapacity dynamic)
            {
                int length = bytes.remaining();
                int space = _byteBuffer.remaining();

                if (length > space)
                    throw new BufferOverflowException();
                if (length > 0)
                {
                    for (RetainableByteBuffer buffer : dynamic._buffers)
                    {
                        buffer.retain();
                        add(buffer);
                    }
                }
                bytes.release();
                return this;
            }

            add(bytes.getByteBuffer());
            bytes.release();
            return this;
        }

        /**
         * Put a {@code byte} to the buffer, growing this buffer if necessary and possible.
         * @param b the {@code byte} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public Mutable put(byte b)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.put(b);
            return this;
        }

        @Override
        public Mutable put(long index, byte b)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);
            int remaining = _byteBuffer.position() - _flipPosition;
            if (index > remaining)
                throw new IndexOutOfBoundsException();
            _byteBuffer.put(_flipPosition + Math.toIntExact(index), b);
            return this;
        }

        /**
         * Put a {@code short} to the buffer, growing this buffer if necessary and possible.
         * @param s the {@code short} to put
         * @throws ReadOnlyBufferException if this buffer is read only.
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public Mutable putShort(short s)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.putShort(s);
            return this;
        }

        /**
         * Put an {@code int} to the buffer, growing this buffer if necessary and possible.
         * @param i the {@code int} to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public Mutable putInt(int i)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.putInt(i);
            return this;
        }

        /**
         * Put a {@code long} to the buffer, growing this buffer if necessary and possible.
         * @param l the {@code long} to put
         * @throws ReadOnlyBufferException if this buffer is read only
         * @throws BufferOverflowException if this buffer cannot fit the byte
         */
        @Override
        public Mutable putLong(long l)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.putLong(l);
            return this;
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
        public Mutable put(byte[] bytes, int offset, int length)
        {
            assert !isRetained();

            // Ensure buffer is flipped to fill mode (and left that way)
            if (_flipPosition < 0)
                _flipPosition = BufferUtil.flipToFill(_byteBuffer);

            _byteBuffer.put(bytes, offset, length);
            return this;
        }

        @Override
        protected void addValueMarker(StringBuilder builder, boolean beginning)
        {
            if (beginning)
            {
                if (_flipPosition >= 0)
                {
                    builder.append("<<~")
                        .append(_flipPosition)
                        .append('-')
                        .append(_byteBuffer.position())
                        .append('/')
                        .append(_byteBuffer.capacity())
                        .append('<');
                }
                else
                {
                    builder.append("<<")
                        .append(_byteBuffer.position())
                        .append('-')
                        .append(_byteBuffer.limit())
                        .append('/')
                        .append(_byteBuffer.capacity())
                        .append('<');
                }
            }
            else
            {
                builder.append(">>>");
            }
        }
    }

    /**
     * A {@link ByteBufferPool pooled} buffer that knows the pool from which it was allocated.
     * Any methods that may need to allocated additional buffers (e.g. {@link #copy()}) will use the pool.
     */
    class Pooled extends FixedCapacity
    {
        private final ByteBufferPool _pool;

        public Pooled(ByteBufferPool pool, ByteBuffer byteBuffer)
        {
            super(byteBuffer);
            _pool = pool;
        }

        protected Pooled(ByteBufferPool pool, ByteBuffer byteBuffer, Retainable retainable)
        {
            super(byteBuffer, retainable);
            _pool = pool;
        }

        @Override
        public boolean releaseAndRemove()
        {
            return _pool.releaseAndRemove(this);
        }

        @Override
        public RetainableByteBuffer slice(long length)
        {
            int size = remaining();
            ByteBuffer byteBuffer = getByteBuffer();
            int limit = byteBuffer.limit();

            byteBuffer.limit(byteBuffer.position() + Math.toIntExact(Math.min(length, size)));
            ByteBuffer slice = byteBuffer.slice();
            byteBuffer.limit(limit);
            if (length > size)
                slice.limit(size);

            if (!canRetain())
                return new NonRetainableByteBuffer(slice);

            retain();
            return new Pooled(_pool, slice, this);
        }

        @Override
        public RetainableByteBuffer copy()
        {
            RetainableByteBuffer copy = _pool.acquire(remaining(), isDirect());
            copy.asMutable().append(getByteBuffer().slice());
            return copy;
        }
    }

    /**
     * a {@link FixedCapacity} buffer that is neither not pooled nor {@link Retainable#canRetain() retainable}.
     */
    class NonRetainableByteBuffer extends FixedCapacity
    {
        public NonRetainableByteBuffer(ByteBuffer byteBuffer)
        {
            super(byteBuffer, NON_RETAINABLE);
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
        private static final Logger LOG = LoggerFactory.getLogger(RetainableByteBuffer.DynamicCapacity.class);

        private final ByteBufferPool.Sized _pool;
        private final long _maxSize;
        private final List<RetainableByteBuffer> _buffers;
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
         * @param sizedPool The pool from which to allocate buffers, with {@link ByteBufferPool.Sized#isDirect()} configured
         *                  and {@link ByteBufferPool.Sized#getSize()} used for the size of aggregation buffers.
         */
        public DynamicCapacity(ByteBufferPool.Sized sizedPool)
        {
            this(null, sizedPool, -1, -1);
        }

        /**
         * @param sizedPool The pool from which to allocate buffers, with {@link ByteBufferPool.Sized#isDirect()} configured
         *                  and {@link ByteBufferPool.Sized#getSize()} used for the size of aggregation buffers.
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         */
        public DynamicCapacity(ByteBufferPool.Sized sizedPool, long maxSize)
        {
            this(null, sizedPool, maxSize, -1);
        }

        /**
         * @param sizedPool The pool from which to allocate buffers, with {@link ByteBufferPool.Sized#isDirect()} configured
         *                  and {@link ByteBufferPool.Sized#getSize()} used for the size of aggregation buffers.
         * @param maxSize The maximum length of the accumulated buffers or -1 for 2GB limit
         * @param minRetainSize The minimal size of a {@link RetainableByteBuffer} before it will be retained; or 0 to always retain; or -1 for a heuristic;
         */
        public DynamicCapacity(ByteBufferPool.Sized sizedPool, long maxSize, int minRetainSize)
        {
            this(null, sizedPool, maxSize, minRetainSize);
        }

        /**
         * @param pool The pool from which to allocate buffers
         */
        public DynamicCapacity(ByteBufferPool pool)
        {
            this(null, pool instanceof ByteBufferPool.Sized sized ? sized : new ByteBufferPool.Sized(pool), -1, -1);
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
            this(null, new ByteBufferPool.Sized(pool, direct, maxSize > 0 && maxSize < IO.DEFAULT_BUFFER_SIZE ? (int)maxSize : aggregationSize), maxSize, minRetainSize);
        }

        private DynamicCapacity(List<RetainableByteBuffer> buffers, ByteBufferPool.Sized pool, long maxSize, int minRetainSize)
        {
            _pool = pool == null ? ByteBufferPool.SIZED_NON_POOLING : pool;
            _maxSize = maxSize < 0 ? Long.MAX_VALUE : maxSize;
            _buffers = buffers == null ? new ArrayList<>() : buffers;

            _minRetainSize = minRetainSize;

            if (_pool.getSize() == 0 && _maxSize >= Integer.MAX_VALUE && _minRetainSize != 0)
                throw new IllegalArgumentException("must always retain if cannot aggregate");
        }

        private void checkNotReleased()
        {
            if (getRetained() <= 0)
                throw new IllegalStateException("Already released");
        }

        public long getMaxSize()
        {
            return _maxSize;
        }

        public int getAggregationSize()
        {
            return _pool.getSize();
        }

        public int getMinRetainSize()
        {
            return _minRetainSize;
        }

        @Override
        public boolean isMutable()
        {
            return true;
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
            if (LOG.isDebugEnabled())
                LOG.debug("getByteBuffer {}", this);
            checkNotReleased();
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
                    RetainableByteBuffer combined = _pool.acquire(length);
                    ByteBuffer byteBuffer = combined.getByteBuffer();
                    BufferUtil.flipToFill(byteBuffer);
                    for (RetainableByteBuffer buffer : _buffers)
                    {
                        byteBuffer.put(buffer.getByteBuffer());
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

        @Override
        public RetainableByteBuffer take(long length)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("take {} {}", this, length);
            checkNotReleased();

            if (_buffers.isEmpty() || length == 0)
                return RetainableByteBuffer.EMPTY;

            _aggregate = null;

            if (_buffers.size() == 1)
            {
                RetainableByteBuffer buffer = _buffers.get(0);

                // if the length to take is more than half the buffer and it is not retained
                if (length > (buffer.size() / 2)  && !buffer.isRetained())
                {
                    // slice off the tail and take the buffer itself
                    RetainableByteBuffer tail = buffer.takeFrom(length);
                    _buffers.set(0, tail);
                    return buffer;
                }

                // take the head of the buffer, but leave the buffer itself
                return buffer.take(length);

            }

            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (ListIterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();

                long size = buffer.size();
                if (length >= size)
                {
                    // take the buffer
                    length -= size;
                    buffers.add(buffer);
                    i.remove();
                    if (length == 0)
                        break;
                }
                else
                {
                    // if the length to take is more than half the buffer and it is not retained
                    if (length > (buffer.size() / 2) && !buffer.isRetained())
                    {
                        // slice off the tail and take the buffer itself
                        RetainableByteBuffer tail = buffer.takeFrom(length);
                        buffers.add(buffer);
                        i.set(tail);
                    }
                    else
                    {
                        // take the head of the buffer, but leave the buffer itself
                        buffers.add(buffer.take(length));
                    }
                    break;
                }
            }
            return new DynamicCapacity(buffers, _pool, _maxSize, _minRetainSize);
        }

        @Override
        public RetainableByteBuffer takeFrom(long skip)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("take {} {}", this, skip);
            checkNotReleased();

            if (_buffers.isEmpty() || skip > size())
                return RetainableByteBuffer.EMPTY;

            _aggregate = null;

            if (_buffers.size() == 1)
            {
                RetainableByteBuffer buffer = _buffers.get(0);
                // if the length to leave is more than half the buffer
                if (skip > (buffer.size() / 2) || buffer.isRetained())
                {
                    // take from the tail of the buffer and leave the buffer itself
                    return buffer.takeFrom(skip);
                }
                // leave the head taken from the buffer and take the buffer itself
                _buffers.set(0, buffer.take(skip));
                return buffer;
            }

            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (ListIterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();

                long size = buffer.size();
                if (skip >= size)
                {
                    // leave this buffer
                    skip -= size;
                }
                else if (skip == 0)
                {
                    buffers.add(buffer);
                    i.remove();
                }
                else
                {
                    // if the length to leave is more than half the buffer
                    if (skip > (buffer.size() / 2) || buffer.isRetained())
                    {
                        // take from the tail of the buffer and leave the buffer itself
                        buffers.add(buffer.takeFrom(skip));
                    }
                    else
                    {
                        // leave the head taken from the buffer and take the buffer itself
                        i.set(buffer.take(skip));
                        buffers.add(buffer);
                    }
                    skip = 0;
                }
            }
            return new DynamicCapacity(buffers, _pool, _maxSize, _minRetainSize);
        }

        /**
         * Take the contents of this buffer, leaving it clear and independent
         * @return A possibly newly allocated array with the contents of this buffer, avoiding copies if possible.
         * The length of the array may be larger than the contents, but the offset will always be 0.
         */
        public byte[] takeByteArray()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("takeByteArray {}", this);
            checkNotReleased();
            return switch (_buffers.size())
            {
                case 0 -> BufferUtil.EMPTY_BUFFER.array();
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    _aggregate = null;
                    _buffers.clear();

                    // The array within the buffer can be used if it is not pooled, is not shared and it exits
                    byte[] array = (!(buffer instanceof Pooled) && !buffer.isRetained() && !buffer.isDirect())
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
            if (LOG.isDebugEnabled())
                LOG.debug("get {}", this);
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("get {} {}", this, index);
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("get array {} {}", this, length);
            checkNotReleased();
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
            return _pool.isDirect();
        }

        @Override
        public boolean hasRemaining()
        {
            checkNotReleased();
            for (RetainableByteBuffer rbb : _buffers)
                if (!rbb.isEmpty())
                    return true;
            return false;
        }

        @Override
        public long skip(long length)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("skip {} {}", this, length);
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("limit {} {}", this, limit);
            checkNotReleased();
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
                    buffer.limit(limit);
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
            if (LOG.isDebugEnabled())
                LOG.debug("slice {}", this);
            checkNotReleased();
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.slice());
            return newSlice(buffers);
        }

        @Override
        public Mutable slice(long length)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("slice {} {}", this, length);
            checkNotReleased();
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
            return new DynamicCapacity(buffers, _pool, _maxSize, _minRetainSize);
        }

        @Override
        public long space()
        {
            return maxSize() - size();
        }

        @Override
        public boolean isFull()
        {
            return size() >= maxSize();
        }

        @Override
        public RetainableByteBuffer copy()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("copy {}", this);
            checkNotReleased();
            List<RetainableByteBuffer> buffers = new ArrayList<>(_buffers.size());
            for (RetainableByteBuffer rbb : _buffers)
                buffers.add(rbb.copy());

            return new DynamicCapacity(buffers, _pool, _maxSize, _minRetainSize);
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
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("release {}", this);
            checkNotReleased();
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
        public boolean releaseAndRemove()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("release {}", this);
            if (super.release())
            {
                for (RetainableByteBuffer buffer : _buffers)
                    buffer.releaseAndRemove();
                _buffers.clear();
                _aggregate = null;
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("clear {}", this);
            checkNotReleased();
            if (_buffers.isEmpty())
                return;
            _aggregate = null;
            for (RetainableByteBuffer rbb : _buffers)
                rbb.release();
            _buffers.clear();
        }

        @Override
        public boolean append(ByteBuffer bytes)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("append BB {} <- {}", this, BufferUtil.toDetailString(bytes));
            checkNotReleased();
            // Cannot mutate contents if retained
            if (isRetained())
                throw new IllegalStateException("Cannot append to a retained instance");

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
                mutable.isMutable() &&
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
                int aggregateSize = _pool.getSize();

                // If we cannot grow, allow a single allocation only if we have not already retained.
                if (aggregateSize == 0 && _buffers.isEmpty() && _maxSize < Integer.MAX_VALUE)
                    aggregateSize = (int)_maxSize;

                aggregateSize = Math.max(length, aggregateSize);
                if (aggregateSize > space)
                    aggregateSize = (int)space;
                _aggregate = _pool.acquire(aggregateSize, _pool.isDirect());
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
                _aggregate = RetainableByteBuffer.wrap(byteBuffer, _aggregate);
            }
        }

        private boolean shouldAggregate(RetainableByteBuffer buffer, long size)
        {
            if (_minRetainSize > 0)
                return size < _minRetainSize;

            if (_minRetainSize == -1)
            {
                // If we are already aggregating and the size is small
                if (_aggregate != null && size < 128)
                    return true;

                // else if there is a lot of wasted space in the buffer
                if (buffer instanceof FixedCapacity)
                    return size < buffer.capacity() / 64;

                // else if it is small
                return size < 128;
            }
            return false;
        }

        @Override
        public boolean append(RetainableByteBuffer retainableBytes)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("append RBB {} {}", this, retainableBytes);
            checkNotReleased();
            // Cannot mutate contents if retained
            if (isRetained())
                throw new IllegalStateException("Cannot append to a retained instance");

            // Optimize appending dynamics
            if (retainableBytes instanceof DynamicCapacity dynamicCapacity)
            {
                for (Iterator<RetainableByteBuffer> i = dynamicCapacity._buffers.iterator(); i.hasNext();)
                {
                    RetainableByteBuffer buffer = i.next();
                    if (!append(buffer))
                        return false;
                    buffer.release();
                    i.remove();
                }
                return true;
            }

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
            if (shouldAggregate(retainableBytes, length))
                return append(retainableBytes.getByteBuffer());

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
        public Mutable add(ByteBuffer bytes) throws ReadOnlyBufferException, BufferOverflowException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("add BB {} <- {}", this, BufferUtil.toDetailString(bytes));
            checkNotReleased();
            add(RetainableByteBuffer.wrap(bytes));
            return this;
        }

        @Override
        public Mutable add(RetainableByteBuffer bytes) throws ReadOnlyBufferException, BufferOverflowException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("add RBB {} <- {}", this, bytes);
            checkNotReleased();
            long size = size();
            long space = _maxSize - size;
            long length = bytes.size();
            if (space < length)
                throw new BufferOverflowException();

            if (shouldAggregate(bytes, length) && append(bytes))
            {
                bytes.release();
                return this;
            }

            _buffers.add(bytes);
            _aggregate = null;
            return this;
        }

        @Override
        public Mutable put(byte b)
        {
            checkNotReleased();
            ensure(1).put(b);
            return this;
        }

        @Override
        public Mutable put(long index, byte b)
        {
            checkNotReleased();
            for (RetainableByteBuffer buffer : _buffers)
            {
                long size = buffer.size();
                if (index < size)
                {
                    buffer.asMutable().put(index, b);
                    return this;
                }
                index -= size;
            }
            throw new IndexOutOfBoundsException();
        }

        @Override
        public Mutable putShort(short s)
        {
            checkNotReleased();
            ensure(2).putShort(s);
            return this;
        }

        @Override
        public Mutable putInt(int i)
        {
            checkNotReleased();
            ensure(4).putInt(i);
            return this;
        }

        @Override
        public Mutable putLong(long l)
        {
            checkNotReleased();
            ensure(8).putLong(l);
            return this;
        }

        @Override
        public Mutable put(byte[] bytes, int offset, int length)
        {
            checkNotReleased();
            // Use existing aggregate if the length is large and there is space for at least half
            if (length >= 16 && _aggregate != null)
            {
                long space = _aggregate.space();
                if (length > space && length / 2 <= space)
                {
                    int s = (int)space;
                    _aggregate.put(bytes, offset, s);
                    offset += s;
                    length -= s;
                }
            }

            ensure(length).put(bytes, offset, length);
            return this;
        }

        private Mutable ensure(int needed) throws BufferOverflowException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ensure {} {}", this, needed);
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
                mutable.isMutable() &&
                mutable.space() >= needed &&
                !mutable.isRetained())
            {
                _aggregate = mutable;
                return _aggregate;
            }

            // We need a new aggregate, acquire a new aggregate buffer
            int aggregateSize = _pool.getSize();
            // If we cannot grow, allow a single allocation only if we have not already retained.
            if (aggregateSize == 0 && _buffers.isEmpty() && _maxSize < Integer.MAX_VALUE)
                _aggregate = _pool.acquire(Math.toIntExact(_maxSize));
            else if (needed > aggregateSize)
                _aggregate = _pool.acquire(needed);
            else
                _aggregate = _pool.acquire();

            // If the new aggregate buffer is larger than the space available, then adjust the capacity
            checkAggregateLimit(space);
            _buffers.add(_aggregate);
            return _aggregate;
        }

        @Override
        public boolean appendTo(ByteBuffer to)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("appendTo BB {} -> {}", this, BufferUtil.toDetailString(to));
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("appendTo RBB {} -> {}", this, to);
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("putTo BB {} -> {}", this, toInfillMode);
            checkNotReleased();
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
            if (LOG.isDebugEnabled())
                LOG.debug("writeTo {} -> {} {} {}", this, sink, last, callback);
            checkNotReleased();
            _aggregate = null;
            switch (_buffers.size())
            {
                case 0 -> callback.succeeded();
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    buffer.writeTo(sink, last, Callback.from(this::clear, callback));
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
                        endPoint.write(Callback.from(this::clear, callback), buffers);
                        return;
                    }

                    // write buffer by buffer
                    new IteratingNestedCallback(callback)
                    {
                        int _index;
                        RetainableByteBuffer _buffer;
                        boolean _lastWritten;

                        @Override
                        protected Action process()
                        {
                            // write next buffer
                            if (_index < _buffers.size())
                            {
                                _buffer = _buffers.get(_index++);
                                _lastWritten = last && (_index == _buffers.size());
                                _buffer.writeTo(sink, _lastWritten, this);
                                return Action.SCHEDULED;
                            }

                            // All buffers written
                            if (last && !_lastWritten)
                            {
                                _buffer = null;
                                _lastWritten = true;
                                sink.write(true, BufferUtil.EMPTY_BUFFER, this);
                                return Action.SCHEDULED;
                            }
                            _buffers.clear();
                            return Action.SUCCEEDED;
                        }

                        @Override
                        protected void onSuccess()
                        {
                            // release the last buffer written
                            _buffer = Retainable.release(_buffer);
                        }

                        @Override
                        protected void onCompleteFailure(Throwable x)
                        {
                            // release the last buffer written
                            _buffer = Retainable.release(_buffer);
                        }
                    }.iterate();
                }
            }
        }

        @Override
        protected void addExtraStringInfo(StringBuilder builder)
        {
            super.addExtraStringInfo(builder);
            builder.append(",pool=");
            builder.append(_pool);
            builder.append(",minRetain=");
            builder.append(_minRetainSize);
            builder.append(",buffers=");
            builder.append(_buffers.size());
        }

        @Override
        protected void addValueString(StringBuilder builder)
        {
            for (RetainableByteBuffer buffer : _buffers)
            {
                builder.append('@');
                builder.append(Integer.toHexString(System.identityHashCode(buffer)));
                if (buffer instanceof Abstract abstractBuffer)
                {
                    builder.append("/r=");
                    builder.append(abstractBuffer.getRetained());
                    abstractBuffer.addValueString(builder);
                }
                else
                {
                    builder.append("???");
                }
            }
        }

        @Override
        protected void addValueMarker(StringBuilder builder, boolean beginning)
        {
            if (beginning)
                builder.append("<<").append(_buffers.size()).append('<');
            else
                builder.append(">>>");
        }
    }
}
