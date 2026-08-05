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

package org.eclipse.jetty.util.buffer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.internal.MultiBuffer;
import org.eclipse.jetty.util.internal.PathBuffer;
import org.eclipse.jetty.util.internal.SingleMutableBuffer;

/// A sequence of bytes that can be read, along with [Retainable] features.
///
/// The bytes may come from different sources, such as:
/// * a single [ByteBuffer]
/// * a list of [ByteBuffer]s
/// * a [FileChannel]
public interface RetainableByteBuffer extends Retainable
{
    /// @return the empty buffer
    static RetainableByteBuffer empty()
    {
        return RetainableByteBuffer.Mutable.empty();
    }

    /// Allocates a ready-to-be-read buffer of the given size and directness.
    ///
    /// @param size the capacity of the buffer
    /// @param direct whether the buffer is direct
    /// @return a new buffer
    static RetainableByteBuffer allocate(int size, boolean direct)
    {
        return wrap(direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size));
    }

    /// Wraps the given byte array, using a [ReferenceCounter] for retainability.
    ///
    /// @param bytes the byte array to wrap
    /// @return a new buffer
    static RetainableByteBuffer wrap(byte[] bytes)
    {
        return bytes == null ? empty() : wrap(bytes, 0, bytes.length);
    }

    /// Wraps the given byte array, starting at the specified `offset` for the given `length`
    /// and using a [ReferenceCounter] for retainability.
    ///
    /// @param bytes the byte array to wrap
    /// @param offset the offset within the array of the first byte to be read
    /// @param length the number of bytes to be read from the given array
    /// @return a new buffer
    static RetainableByteBuffer wrap(byte[] bytes, int offset, int length)
    {
        return bytes == null ? empty() : wrap(ByteBuffer.wrap(bytes, offset, length));
    }

    /// Wraps the given [ByteBuffer], using a [ReferenceCounter] for retainability.
    ///
    /// @param byteBuffer the [ByteBuffer] to wrap
    /// @return a new buffer
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer)
    {
        return byteBuffer == null ? empty() : wrap(byteBuffer, new ReferenceCounter());
    }

    /// Wraps the given [ByteBuffer], using the provided [Retainable] for retainability.
    ///
    /// @param byteBuffer the [ByteBuffer] to wrap
    /// @param retainable the [Retainable] used for retainability
    /// @return a new buffer
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new SingleMutableBuffer(byteBuffer, retainable, false);
    }

    /// Wraps the given [String], encoded to bytes using the given [Charset],
    /// using a [ReferenceCounter] for retainability.
    ///
    /// @param string the [String] to wrap
    /// @param charset the [Charset] to use for encoding
    /// @return a new buffer
    static RetainableByteBuffer wrap(String string, Charset charset)
    {
        return wrap(charset.encode(string));
    }

    /// Wraps the given [ByteBuffer]s, using a [ReferenceCounter] for retainability.
    ///
    /// @param buffers the [ByteBuffer]s to wrap
    /// @return a new buffer
    static RetainableByteBuffer wrap(ByteBuffer... buffers)
    {
        if (BufferUtil.isEmpty(buffers))
            return empty();
        if (buffers.length == 1)
            return wrap(buffers[0]);
        List<RetainableByteBuffer> rbs = Arrays.stream(buffers).map(RetainableByteBuffer::wrap).toList();
        return new MultiBuffer(rbs);
    }

    /// Wraps the given buffer list, using a new [ReferenceCounter] for retainability.
    ///
    /// @param buffers the buffer list to wrap
    /// @return a new buffer
    static RetainableByteBuffer wrap(List<RetainableByteBuffer> buffers)
    {
        if (buffers.isEmpty())
            return empty();
        return new MultiBuffer(buffers);
    }

    /// Wraps the given array of buffers, using a new [ReferenceCounter] for retainability.
    ///
    /// @param buffers the array of buffers to wrap
    /// @return a new buffer
    static RetainableByteBuffer wrap(RetainableByteBuffer... buffers)
    {
        List<RetainableByteBuffer> list = new ArrayList<>(buffers.length);
        for (RetainableByteBuffer buffer : buffers)
        {
            if (buffer != null && buffer.hasRemaining())
                list.add(buffer);
        }
        return wrap(list);
    }

    /// Wraps the given [Path], using a new [ReferenceCounter] for retainability.
    ///
    /// @param path the [Path] to wrap
    /// @param pool the buffer pool to use to allocate buffers to read the [Path]
    /// @return a new buffer
    static RetainableByteBuffer wrap(Path path, WritableBufferPool.Sized pool) throws IOException
    {
        return new PathBuffer(path, 0L, -1L, pool);
    }

    /// Wraps the given [Path], using a new [ReferenceCounter] for retainability.
    ///
    /// @param path the [Path] to wrap
    /// @param offset the offset within the file to start reading from
    /// @param length the number of bytes to read from the file
    /// @param pool the buffer pool to use to allocate buffers to read the [Path]
    /// @return a new buffer
    static RetainableByteBuffer wrap(Path path, long offset, long length, WritableBufferPool.Sized pool) throws IOException
    {
        if (length == 0L)
            return empty();
        return new PathBuffer(path, offset, length, pool);
    }

    /// Returns the current read position of this buffer.
    /// This value always lies between `0` and [#capacity()].
    ///
    /// @return the current read position
    long readPosition();

    /// Changes the current read position of this buffer.
    /// Must always be between `0` and [#capacity()].
    ///
    /// @param newPosition the new read position
    /// @return this buffer
    RetainableByteBuffer readPosition(long newPosition);

    /// Consumes `length` bytes from the current [#readPosition()].
    ///
    /// @param length the number of bytes to consume
    default RetainableByteBuffer consume(long length)
    {
        return readPosition(readPosition() + length);
    }

    /// @return the capacity of this buffer
    long capacity();

    /// Returns the number of bytes that can be read, between [#readPosition()] and [Mutable#writePosition()].
    ///
    /// @return the number of bytes that can be read
    long remaining();

    /// @return whether there is at least 1 [#remaining()] byte that can be read
    default boolean hasRemaining()
    {
        return remaining() > 0;
    }

    /// Reads a single `byte` at the current [#readPosition()], and advances the read position by one.
    ///
    /// @throws BufferUnderflowException if there are no bytes to read
    byte get();

    /// Reads a single `byte` at the specified absolute read position.
    /// Does not advance [#readPosition()].
    ///
    /// @param index the absolute read position of the byte to read
    /// @throws BufferUnderflowException if there are no bytes to read at the given read position
    byte get(long index);

    /// Reads a single `byte` at the current [#readPosition()], converted to `int` via `get() & 0xFF`.
    ///
    /// @return the `byte` converted to `int`
    /// @throws BufferUnderflowException if there are no bytes to read
    /// @see #get()
    default int getByteAsInt()
    {
        return get() & 0xFF;
    }

    /// Reads a single `byte` at the specified absolute [#readPosition()].
    ///
    /// Does not advance the read position.
    ///
    /// @param index the absolute read position of the byte to read
    /// @return the `byte` converted to `int`
    default int getByteAsInt(long index)
    {
        return get(index) & 0xFF;
    }

    /// Reads a short at the current position.
    ///
    /// @throws BufferUnderflowException – If the buffer's [#remaining()] is less than two.
    short getShort();

    /**
     * Reads a short at the specified absolute position.
     *
     * @param index the absolute position of the short to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than two.
     */
    short getShort(long index);

    /**
     * Reads a short at the current position, converted to `int` via `get() &amp; 0xFFFF`
     *
     * @throws BufferUnderflowException if the buffer's {@link #remaining()} is less than two.
     * @see #get()
     */
    default int getShortAsInt()
    {
        return getShort() & 0xFFFF;
    }

    /**
     * Reads an int at the current position.
     *
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than four.
     */
    int getInt();

    /**
     * Reads an int at the specified absolute position.
     *
     * @param index the absolute position of the int to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than four.
     */
    int getInt(long index);

    /**
     * Reads a long at the current position.
     *
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than eight.
     */
    long getLong();

    /**
     * Reads a long at the specified absolute position.
     *
     * @param index the absolute position of the long to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than eight.
     */
    long getLong(long index);

    /**
     * Reads a byte array at the current position.
     *
     * @param b the byte array to read into
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than the array's length.
     */
    default void get(byte[] b)
    {
        get(b, 0, b.length);
    }

    /**
     * Reads a byte array at the current position.
     *
     * @param b the byte array to read into
     * @param off the offset within the array of the first byte to be read
     * @param len the number of bytes to be read from the given array
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than the array's length.
     */
    void get(byte[] b, int off, int len);

    void get(long index, byte[] b, int off, int len);

    default byte[] getArray()
    {
        byte[] bytes = new byte[Math.toIntExact(remaining())];
        get(bytes);
        return bytes;
    }

    default byte[] getArray(long index)
    {
        byte[] bytes = new byte[Math.toIntExact(remaining())];
        get(index, bytes, 0, bytes.length);
        return bytes;
    }

    default String getString(Charset charset)
    {
        return new String(getArray(), charset);
    }

    default String getString(long index, Charset charset)
    {
        return new String(getArray(index), charset);
    }

    /// Slices this buffer, equivalent to `slice(readPosition(), remaining())`.
    ///
    /// @return a slice of this buffer
    default RetainableByteBuffer slice()
    {
        return slice(readPosition(), remaining());
    }

    /// Creates a new buffer that is a shared view of this buffer from the given read position and for the given length.
    ///
    /// The slice operation retains this buffer, and the returned slice must be released.
    ///
    /// The returned slice read position is zero and the capacity is `length`.
    ///
    /// @param position the read position of the current buffer
    /// @param length the length of the slice.
    /// @return a slice of this buffer
    RetainableByteBuffer slice(long position, long length);

    /// Slices this buffer from the current read position and for the given length,
    /// and [consumes][#consume(long)] this buffer by the given length.
    ///
    /// @param length the length of the slice.
    /// @return a slice of this buffer
    default RetainableByteBuffer sliceAndConsume(long length)
    {
        RetainableByteBuffer slice = slice(readPosition(), length);
        consume(length);
        return slice;
    }

    /// Slices this buffer and passes the slice to the given mapping function.
    ///
    /// The slice is automatically released when the mapping function returns.
    ///
    /// @param mapper the mapping function
    /// @return the result of the mapping function
    default <T> T mapSlice(Function<RetainableByteBuffer, T> mapper)
    {
        return mapSlice(readPosition(), remaining(), mapper);
    }

    /// Slices this buffer and passes the slice to the given mapping function.
    ///
    /// The slice is automatically released when the mapping function returns.
    ///
    /// @param index the read position of the current buffer
    /// @param length the length of the slice
    /// @param mapper the mapping function
    /// @return the result of the mapping function
    default <T> T mapSlice(long index, long length, Function<RetainableByteBuffer, T> mapper)
    {
        return mapSlice(slice(index, length), mapper);
    }

    private static <T> T mapSlice(RetainableByteBuffer slice, Function<RetainableByteBuffer, T> mapper)
    {
        try
        {
            return mapper.apply(slice);
        }
        finally
        {
            slice.release();
        }
    }

    /// Offers the contents of this buffer to be read by the given [Target].
    ///
    /// @param target the [Target] that reads this buffer
    /// @return the number of bytes read
    /// @throws IOException when an IOException occurs
    long writeTo(Target target) throws IOException;

    default long quietWriteTo(Target target)
    {
        try
        {
            return writeTo(target);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Base interface of the Target (i.e.: byte destination) used to flush a ReadableBuffer via the NIO ByteBuffer API.
     */
    // TODO: rename to Reader? And Fount to Writer?
    interface Target
    {
        /// Implementations get bytes from the given [ByteBuffer].
        ///
        /// This method may be called multiple times if it wraps multiple [ByteBuffer]s.
        ///
        ///
        ///
        /// Note that this method can be called more than once if the `input` byte buffer
        /// is depleted, for instance, if the WritableBuffer is backed by more than one NIO ByteBuffer.
        ///
        /// @param input the buffer to be written
        /// @throws IOException when IOException occurs
        long write(ByteBuffer input) throws IOException;
    }

    /**
     * Interface of the Target (i.e.: byte destination) used to flush a ReadableBuffer via the NIO ByteBuffer API when the
     * target supports gathering writes.
     */
    interface GatheringTarget extends Target
    {
        /**
         * Flushes a given NIO ByteBuffer array.
         *
         * @param inputs the buffer to be written
         * @throws IOException when IOException occurs
         */
        long write(ByteBuffer[] inputs) throws IOException;
    }

    /**
     * Interface of the Target (i.e.: byte destination) used to flush a ReadableBuffer backed by a FileChannel.
     * This is meant to be used when the target can perform the copy via NIO FileChannel.transferTo().
     */
    interface TransferringTarget extends Target
    {
        /**
         * Flushes a given FileChannel from the given position, up to the given count.
         *
         * @param input the source FileChannel
         * @param position the position in the source FileChannel; always non-negative
         * @param count the maximum number of bytes to be transferred; always non-negative
         * @return the number of bytes that were transferred
         * @throws IOException when IOException occurs
         */
        long write(FileChannel input, long position, long count) throws IOException;
    }

    /**
     * Wraps a byte container, exposing a write-only API. The byte container could be for instance:
     * <ul>
     *  <li>a single NIO ByteBuffer</li>
     *  <li>a list of NIO ByteBuffers</li>
     *  <li>a FileChannel</li>
     *  </ul>
     */
    interface Mutable extends RetainableByteBuffer
    {
        /// @return the empty buffer
        static RetainableByteBuffer.Mutable empty()
        {
            return SingleMutableBuffer.Empty.INSTANCE;
        }

        static Mutable wrap(byte[] bytes)
        {
            return wrap(ByteBuffer.wrap(bytes));
        }

        /**
         * Wraps the given NIO ByteBuffer that already is in fill node, using a new {@link ReferenceCounter} for
         * handling the release.
         *
         * @param byteBuffer the NIO byte buffer
         * @return a WritableBuffer
         */
        static Mutable wrap(ByteBuffer byteBuffer)
        {
            return wrap(byteBuffer, new ReferenceCounter());
        }

        /**
         * Wraps the given NIO ByteBuffer that already is in fill node.
         *
         * @param byteBuffer the NIO byte buffer
         * @param retainable use the given {@link Retainable} for handling the release
         * @return a WritableBuffer
         */
        static Mutable wrap(ByteBuffer byteBuffer, Retainable retainable)
        {
            Objects.requireNonNull(byteBuffer);
            if (byteBuffer.isReadOnly())
                throw new ReadOnlyBufferException();
            return new SingleMutableBuffer(byteBuffer, retainable, true);
        }

        /// Allocates a ready-to-be-written buffer of the given size and directness.
        ///
        /// @param size the size of the buffer
        /// @param direct true for a direct buffer, false for a heap buffer
        /// @return a new buffer
        static Mutable allocate(int size, boolean direct)
        {
            return new SingleMutableBuffer(direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size), new ReferenceCounter(), true);
        }

        /**
         * Changes the byte order of this WritableBuffer.
         *
         * @param byteOrder true for little endian, false for big endian
         */
        void byteOrder(ByteOrder byteOrder);

        /**
         * Returns the current position of this WritableBuffer, where the next bytes are to be written.
         * This value always lies between 0 and {@link #capacity()}.
         *
         * @return the current position
         */
        long writePosition();

        Mutable writePosition(long position);

        /// Fills this buffer with `length` number of the byte `0`, starting at the current write position.
        ///
        /// @return this buffer
        Mutable pad(long length);

        /**
         * Returns how many spare bytes are left for writing, between {@link #writePosition()} and {@link #capacity()}.
         *
         * @return how many spare bytes are left for writing
         */
        long space();

        /**
         * Writes a single byte at the current position.
         *
         * @param b the byte to write
         * @throws java.nio.BufferOverflowException if this buffer's current position is not smaller than its capacity
         */
        Mutable put(byte b);

        /**
         * Writes a single byte at the given position.
         *
         * @param position the position of the byte
         * @param b the byte to write
         * @throws java.nio.BufferOverflowException if this buffer's current position is not smaller than its capacity
         */
        Mutable put(long position, byte b);

        /**
         * Writes a short at the current position.
         *
         * @param s the short to write
         * @throws java.nio.BufferOverflowException if there are fewer than two bytes remaining in this buffer
         */
        Mutable putShort(short s);

        /**
         * Writes a `short` at the given position.
         *
         * @param position the position to write the `short`
         * @param s the `short` to write
         * @throws java.nio.BufferOverflowException if there are fewer than two bytes remaining in this buffer
         */
        Mutable putShort(long position, short s);

        /**
         * Writes an int at the current position.
         *
         * @param i the int to write
         * @throws java.nio.BufferOverflowException if there are fewer than four bytes remaining in this buffer
         */
        Mutable putInt(int i);

        Mutable putInt(long position, int i);

        /**
         * Writes a long at the current position.
         *
         * @param l the long to write
         * @throws java.nio.BufferOverflowException if there are fewer than eight bytes remaining in this buffer
         */
        Mutable putLong(long l);

        Mutable putLong(long position, long l);

        /**
         * Writes the given source array at the current position.
         *
         * @param src the array from which bytes are to be read
         */
        default Mutable put(byte[] src)
        {
            return put(src, 0, src.length);
        }

        /**
         * Writes the given source array at the current position.
         *
         * @param src the array from which bytes are to be read
         * @param offset the offset within the array of the first byte to be read
         * @param length the number of bytes to be read from the given array
         */
        Mutable put(byte[] src, int offset, int length);

        Mutable put(ByteBuffer byteBuffer);

        /**
         * Writes a {@link RetainableByteBuffer} at the current position.
         *
         * @param readableBuffer the buffer to write
         * @throws java.nio.BufferOverflowException if there is insufficient space in this buffer for the remaining bytes in the source buffer
         */
        Mutable put(RetainableByteBuffer readableBuffer);

        /// Copies the given [ByteBuffer] into this buffer.
        ///
        /// The operation copies up to `n` bytes where `n` is the number of
        /// remaining bytes in the given [ByteBuffer].
        ///
        /// Differently from [#put(ByteBuffer)], this method does not throw
        /// when not all the `n` bytes can be copied, but rather copies
        /// as many bytes as possible and returns the number of copied bytes.
        ///
        /// @param byteBuffer the [ByteBuffer] to copy
        /// @return the number of copied bytes
        long append(ByteBuffer byteBuffer);

        /// Copies the given [RetainableByteBuffer] into this buffer.
        ///
        /// The operation copies up to `n` bytes where `n` is the number of
        /// remaining bytes in the given [RetainableByteBuffer].
        ///
        /// Differently from [#put(RetainableByteBuffer)], this method does not throw
        /// when not all the `n` bytes can be copied, but rather copies
        /// as many bytes as possible and returns the number of copied bytes.
        ///
        /// @param buffer the [RetainableByteBuffer] to copy
        /// @return the number of copied bytes
        long append(RetainableByteBuffer buffer);

        /**
         * Compacts this ReadableBuffer, by flipping it to a {@link Mutable} with the unread bytes (between
         * {@link #readPosition()} and {@link #space()}) moved to position 0.
         *
         * @return this, typed as a {@link Mutable}
         */

        /// Compacts this buffer.
        ///
        /// Unread bytes, if any, are moved to position `0`.
        ///
        /// @return this buffer
        Mutable compact();

        /// Clears this buffer.
        ///
        /// Unread bytes are discarded.
        ///
        /// @return this buffer
        Mutable clear();

        /**
         * Fills this buffer with the given Fount.
         *
         * @param fount the fount
         * @return the # of bytes read, or -1 if EOF was reached
         * @throws IOException when an IOException occurs
         */
        long readFrom(Fount fount) throws IOException;

        default long quietReadFrom(Fount fount)
        {
            try
            {
                return readFrom(fount);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Base interface of the Fount (i.e.: byte source) used to fill a WritableBuffer via the NIO ByteBuffer API.
         */
        interface Fount
        {
            /// Implementations put bytes into the given [ByteBuffer].
            ///
            /// @param output the [ByteBuffer] to write into
            /// @return the number of bytes written
            long read(ByteBuffer output) throws IOException;
        }
    }
}
