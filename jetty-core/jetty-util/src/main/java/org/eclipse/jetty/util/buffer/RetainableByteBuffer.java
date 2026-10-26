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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ByteChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.internal.MultiBuffer;
import org.eclipse.jetty.util.buffer.internal.PathBuffer;
import org.eclipse.jetty.util.buffer.internal.SingleMutableBuffer;

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
        if (byteBuffer == null)
            return empty();
        try (ReferenceCounter rc = new ReferenceCounter())
        {
            return wrap(byteBuffer, rc);
        }
    }

    /// Wraps the given [ByteBuffer], running the given release action when
    /// this buffer is released.
    ///
    /// @param byteBuffer the [ByteBuffer] to wrap
    /// @param releaser the action to run when this buffer is released
    /// @return a new buffer
    static RetainableByteBuffer wrap(ByteBuffer byteBuffer, Runnable releaser)
    {
        ReferenceCounter refCount = new ReferenceCounter()
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
        try (refCount)
        {
            return wrap(byteBuffer, refCount);
        }
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

    /// Merges the given [ByteBuffer]s into a single buffer.
    ///
    /// The given [ByteBuffer]s are sliced into the returned buffer and then consumed.
    ///
    /// The returned buffer must be released.
    ///
    /// @param buffers the [ByteBuffer]s to merge
    /// @return a new buffer
    static RetainableByteBuffer merge(ByteBuffer... buffers)
    {
        return merge(Arrays.stream(buffers).map(RetainableByteBuffer::wrap).toList());
    }

    /// Merges the given buffers into a single buffer.
    ///
    /// The given buffers are sliced into the returned buffer and then consumed.
    ///
    /// The returned buffer must be released.
    ///
    /// @param buffers the array of buffers to wrap
    /// @return a new buffer
    static RetainableByteBuffer merge(RetainableByteBuffer... buffers)
    {
        // Cannot use List.of() because it does not allow null elements.
        List<RetainableByteBuffer> list = new ArrayList<>();
        Collections.addAll(list, buffers);
        return merge(list);
    }

    /// Merges the given buffers into a single buffer.
    ///
    /// The given buffers are sliced into the returned buffer and then consumed.
    ///
    /// The returned buffer must be released.
    ///
    /// @param buffers the buffers to merge
    /// @return a new buffer
    static RetainableByteBuffer merge(List<RetainableByteBuffer> buffers)
    {
        return MultiBuffer.merge(buffers);
    }

    /// Wraps the given [Path], using a new [ReferenceCounter] for retainability.
    ///
    /// @param path the [Path] to wrap
    /// @param pool the buffer pool to use to allocate buffers to read the [Path]
    /// @return a new buffer
    static RetainableByteBuffer wrap(Path path, ByteBufferPool.Sized pool)
    {
        return wrap(path, 0, -1, pool);
    }

    /// Wraps the given [Path], using a new [ReferenceCounter] for retainability.
    ///
    /// @param path the [Path] to wrap
    /// @param offset the offset within the file to start reading from
    /// @param length the number of bytes to read from the file
    /// @param pool the buffer pool to use to allocate buffers to read the [Path]
    /// @return a new buffer
    static RetainableByteBuffer wrap(Path path, long offset, long length, ByteBufferPool.Sized pool)
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
    void readPosition(long newPosition);

    /// Reads and discards `length` bytes from the current [#readPosition()].
    ///
    /// @param length the number of bytes to read and discard
    default void consume(long length)
    {
        readPosition(readPosition() + length);
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

    /// Reads a `byte` at the current read position.
    /// Advances the read position by `1`.
    ///
    /// @throws BufferUnderflowException if there are no bytes to read
    byte get();

    /// Reads a `byte` at the specified absolute read position.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position of the byte to read
    byte get(long position);

    /// Reads a `byte` at the current read position, converted to `int` by masking with `0xFF`.
    /// Advances the read position by `1`.
    ///
    /// @return the `byte` converted to `int`
    /// @throws BufferUnderflowException if there are no bytes to read
    /// @see #get()
    default int getByteAsInt()
    {
        return get() & 0xFF;
    }

    /// Reads a `byte` at the specified absolute read position, converted to `int`
    /// by masking with `0xFF`.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position of the byte to read
    /// @return the `byte` converted to `int`
    default int getByteAsInt(long position)
    {
        return get(position) & 0xFF;
    }

    /// Reads a `short` at the current read position.
    /// Advances the read position by `2`.
    ///
    /// @throws BufferUnderflowException if there are no bytes to read
    short getShort();

    /// Reads a `short` at the specified absolute read position.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position of the `short` to read
    short getShort(long position);

    /// Reads a `short` at the current read position, converted to `int`
    /// by masking with `0xFFFF`.
    /// Advances the read position by `2`.
    ///
    /// @throws BufferUnderflowException if there are no bytes to read
    /// @see #getShort()
    default int getShortAsInt()
    {
        return getShort() & 0xFFFF;
    }

    /// Reads an `int` at the current read position.
    /// Advances the read position by `4`.
    ///
    /// @throws BufferUnderflowException if there are no bytes to read
    int getInt();

    /// Reads an `int` at the specified absolute read position.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position of the `int` to read
    int getInt(long position);

    /// Reads a `long` at the current read position.
    /// Advances the read position by `8`.
    ///
    /// @throws BufferUnderflowException if there are no bytes to read
    long getLong();

    /// Reads an `int` at the specified absolute read position.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position of the `long` to read
    long getLong(long position);

    /// Reads `n` bytes into the given byte array to offset `0`
    /// from the current read position, where `n` is the array length.
    /// Advances the read position by `n`.
    ///
    /// @param b the byte array to read into
    /// @throws BufferUnderflowException if there are not enough bytes to read
    default void get(byte[] b)
    {
        get(b, 0, b.length);
    }

    /// Reads `len` bytes into the given byte array to offset `offset`
    /// from the current position.
    /// Advances the read position by `len`.
    ///
    /// @param b the byte array to read into
    /// @param off the offset within the array
    /// @param len the number of bytes to be read
    /// @throws BufferUnderflowException if there are not enough bytes to read
    void get(byte[] b, int off, int len);

    /// Reads `len` bytes into the given byte array to offset `offset`
    /// from the specified absolute read position.
    /// Advances the read position by `len`.
    ///
    /// @param position the absolute read position to read
    /// @param b the byte array to read into
    /// @param off the offset within the array
    /// @param len the number of bytes to be read
    void get(long position, byte[] b, int off, int len);

    /// Reads the bytes of this buffer into a byte array,
    /// from the current read position for the number of remaining bytes.
    /// The read position is advanced by the length of the byte array,
    /// and this buffer will have no remaining bytes to read.
    ///
    /// @return a byte array with the remaining bytes of this buffer
    default byte[] getArray()
    {
        byte[] bytes = new byte[Math.toIntExact(remaining())];
        get(bytes);
        return bytes;
    }

    /// Reads the bytes of this buffer into a byte array,
    /// from the specified absolute read position to the last readable byte.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position to read from
    /// @return a byte array with the bytes of this buffer from the specified absolute read position
    default byte[] getArray(long position)
    {
        return getArray(position, remaining() + readPosition() - position);
    }

    /// Reads the bytes of this buffer into a byte array,
    /// from the specified absolute read position for the specified number of bytes.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position to read from
    /// @param length the number of bytes to read
    /// @return a byte array with the specified number of bytes of this buffer from the specified absolute read position
    default byte[] getArray(long position, long length)
    {
        byte[] bytes = new byte[Math.toIntExact(length)];
        get(position, bytes, 0, bytes.length);
        return bytes;
    }

    /// Reads the bytes of this buffer into a string using the given [Charset],
    /// from the current read position for the number of remaining bytes.
    /// The read position is advanced by the number of bytes of the string,
    /// and this buffer will have no remaining bytes to read.
    ///
    /// @param charset the [Charset] to use to convert the bytes
    /// @return a new [String] from the buffer bytes
    default String getString(Charset charset)
    {
        return new String(getArray(), charset);
    }

    /// Reads the bytes of this buffer into a string using the given [Charset],
    /// from the specified absolute read position to the last readable byte.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position to start reading from
    /// @param charset the [Charset] to use to convert the bytes
    /// @return a new [String] from the buffer bytes
    default String getString(long position, Charset charset)
    {
        return new String(getArray(position), charset);
    }

    /// Reads the bytes of this buffer into a string using the given [Charset],
    /// from the specified absolute read position for the given number of bytes.
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position to start reading from
    /// @param length the number of bytes to read
    /// @param charset the [Charset] to use to convert the bytes
    /// @return a new [String] from the buffer bytes
    default String getString(long position, long length, Charset charset)
    {
        return new String(getArray(position, length), charset);
    }

    /// Reads the bytes of this buffer into a [ByteBuffer],
    /// from the current read position for the number of remaining bytes.
    /// The read position is advanced by the number of bytes of the [ByteBuffer],
    /// and this buffer will have no remaining bytes to read.
    ///
    /// @param direct whether the returned [ByteBuffer] is direct
    /// @return a new [ByteBuffer] from the buffer bytes
    default ByteBuffer getByteBuffer(boolean direct)
    {
        int length = Math.toIntExact(remaining());
        ByteBuffer result = direct ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
        quietRead(b ->
        {
            int r = b.remaining();
            result.put(b);
            return r;
        });
        return result.flip();
    }

    /// Slices this buffer, equivalent to [`slice(readPosition(), remaining())`][#slice(long, long)].
    ///
    /// @return a slice of this buffer
    default RetainableByteBuffer slice()
    {
        return slice(readPosition(), remaining());
    }

    /// Slices this buffer, equivalent to [`slice(readPosition(), length)`][#slice(long, long)].
    ///
    /// @param length the length of the slice
    /// @return a slice of this buffer
    default RetainableByteBuffer slice(long length)
    {
        return slice(readPosition(), length);
    }

    /// Creates a new buffer that is a shared view of this buffer from the specified absolute
    /// read position and for the given length.
    ///
    /// The slice operation retains this buffer, and the returned slice must be released.
    ///
    /// The returned slice read position is `0` and the capacity is `length`.
    ///
    /// Does not advance the read position.
    ///
    /// @param position the absolute read position to start slicing from
    /// @param length the length of the slice
    /// @return a slice of this buffer
    RetainableByteBuffer slice(long position, long length);

    /// Slices this buffer from the current read position and for the given length,
    /// and [consumes][#consume(long)] this buffer by the given length.
    ///
    /// @param length the length of the slice.
    /// @return a slice of this buffer
    default RetainableByteBuffer sliceAndConsume(long length)
    {
        RetainableByteBuffer slice = slice(length);
        consume(length);
        return slice;
    }

    /// Offers the contents of this buffer to be read by the given [Reader].
    ///
    /// When this buffer is backed by a single byte source, the value returned
    /// by invoking the [Reader] is also returned by this method.
    ///
    /// When this buffer is backed by multiple byte sources, the [Reader] may
    /// be invoked multiple times.
    /// If an invocation returns less than the byte source available bytes,
    /// then the next invocation is not performed.
    ///
    /// If the value returned by the [Reader] is negative, it is returned
    /// by this method, no matter if previous invocations returned non-negative
    /// values.
    ///
    /// Otherwise, the [Reader] invocations returned non-negative values,
    /// and this method returns the sum of the values of each invocation.
    ///
    /// @param reader the [Reader] that reads this buffer
    /// @return the number of bytes read, or a negative value returned by the [Reader]
    /// @throws IOException when an IOException occurs
    long read(Reader reader) throws IOException;

    /// Wraps [#read(Reader)] so that [IOException]s are wrapped in [UncheckedIOException]s.
    ///
    /// @param reader the [Reader] that reads this buffer
    /// @return the number of bytes read, or a negative value returned by the [Reader]
    default long quietRead(Reader reader)
    {
        try
        {
            return read(reader);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    /// Copies the content of this buffer to the given [OutputStream].
    ///
    /// @param stream the [OutputStream] to write bytes to
    /// @return the number of bytes written
    /// @throws IOException if the write fails
    default long putTo(OutputStream stream) throws IOException
    {
        return read(b ->
        {
            int r = b.remaining();
            stream.write(BufferUtil.toArray(b));
            return r;
        });
    }

    /// Copies the content of this buffer to the given [ByteBuffer].
    ///
    /// The operation tries to copy `n` bytes where `n` is the
    /// number of remaining bytes in this buffer.
    /// If there is not enough space in the given [ByteBuffer],
    /// then [BufferOverflowException] is thrown.
    /// The given [ByteBuffer] position is advanced by the number of
    /// bytes copied.
    ///
    /// @param output the [ByteBuffer] to copy bytes into
    /// @return the number of bytes copied
    default int putTo(ByteBuffer output)
    {
        return Math.toIntExact(quietRead(b ->
        {
            int r = b.remaining();
            output.put(b);
            return r;
        }));
    }

    /// Copies the content of this buffer to the given [ByteBuffer].
    ///
    /// The operation copies up to `n` bytes where `n` is the number
    /// of remaining bytes in this buffer.
    /// The number of bytes copied is capped by the space available
    /// in the given [ByteBuffer].
    /// The given [ByteBuffer] position is advanced by the number of
    /// bytes copied.
    ///
    /// @param output the [ByteBuffer] to copy bytes into
    /// @return the number of bytes copied
    default int appendTo(ByteBuffer output)
    {
        return Math.toIntExact(quietRead(b ->
        {
            int remaining = b.remaining();
            int space = output.remaining();
            if (remaining <= space)
            {
                output.put(b);
                return remaining;
            }
            else
            {
                int limit = b.limit();
                b.limit(b.position() + space);
                output.put(b);
                b.limit(limit);
                return space;
            }
        }));
    }

    /// Base interface to read from this buffer via its internal [ByteBuffer]\(s).
    ///
    /// This interface is used to bridge this buffer to APIs that work with [ByteBuffer],
    /// such as [ByteChannel#write(ByteBuffer)].
    ///
    /// If this buffer wraps multiple [ByteBuffer]s, the reader is invoked multiple times.
    interface Reader
    {
        /// Implementations read bytes from the given [ByteBuffer].
        ///
        /// This method may be called multiple times if it wraps multiple [ByteBuffer]s.
        ///
        /// @param input the [ByteBuffer] to be read
        /// @return the number of bytes read, or a negative value
        /// @throws IOException when IOException occurs
        long readFrom(ByteBuffer input) throws IOException;
    }

    /// Specialized interface to read from this buffer from its internal [ByteBuffer]s at once.
    ///
    /// This interface is used to bridge this buffer to APIs that work with multiple [ByteBuffer]s,
    /// such as [GatheringByteChannel#write(ByteBuffer\[\], int, int)].
    interface GatheringReader extends Reader
    {
        /// Implementations read bytes from the given [ByteBuffer]s.
        ///
        /// @param inputs the array of [ByteBuffer]s to be read
        /// @param offset the index of the array containing the first [ByteBuffer] to read
        /// @param length the number of [ByteBuffer] to read
        /// @return the number of bytes read, or a negative value
        /// @throws IOException when IOException occurs
        long readFrom(ByteBuffer[] inputs, int offset, int length) throws IOException;
    }

    /// Specialized interface to read from this buffer via its internal [FileChannel].
    ///
    /// This interface is used to bridge this buffer to APIs that work with [FileChannel],
    /// such as [FileChannel#transferTo(long, long, WritableByteChannel)].
    interface TransferringReader extends Reader
    {
        /// Implementations transfer bytes from the given [FileChannel].
        ///
        /// @param input the [FileChannel] to transfer from
        /// @param position the position in the [FileChannel] to start transfer from, must be non-negative
        /// @param count the maximum number of bytes to be transferred, must be non-negative
        /// @return the number of transferred bytes, or a negative value
        /// @throws IOException when IOException occurs
        long readFrom(FileChannel input, long position, long count) throws IOException;
    }

    /// Extends [RetainableByteBuffer] exposing a write API.
    interface Mutable extends RetainableByteBuffer
    {
        /// @return the empty buffer
        static RetainableByteBuffer.Mutable empty()
        {
            return SingleMutableBuffer.Empty.INSTANCE;
        }

        /// @param bytes the byte array to wrap
        /// @return a new buffer wrapping the given byte array
        static Mutable wrap(byte[] bytes)
        {
            return wrap(ByteBuffer.wrap(bytes));
        }

        /// @param byteBuffer the [ByteBuffer] to wrap
        /// @return a new buffer wrapping the given [ByteBuffer]
        static Mutable wrap(ByteBuffer byteBuffer)
        {
            try (ReferenceCounter rc = new ReferenceCounter())
            {
                return wrap(byteBuffer, rc);
            }
        }

        /// Wraps the given [ByteBuffer] with the given [Retainable].
        ///
        /// @param byteBuffer the [ByteBuffer] to wrap
        /// @param retainable the [Retainable] for the lifecycle
        /// @return a new buffer wrapping the given [ByteBuffer] and [Retainable]
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
            try (ReferenceCounter rc = new ReferenceCounter())
            {
                return new SingleMutableBuffer(direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size), rc, true);
            }
        }

        /// Returns the current write position of this buffer.
        /// This value always lies between 0 and [#capacity()].
        ///
        /// @return the current write position
        long writePosition();

        /// Changes the current write position of this buffer.
        /// Must always be between `0` and [#capacity()].
        ///
        /// @param position the new write position
        void writePosition(long position);

        /// Fills this buffer with `length` number of the byte `0`, starting at the current write position.
        ///
        /// @param length the number of bytes to pad
        void pad(long length);

        /// @return how many bytes are left for writing
        long space();

        /// Writes a `byte` at the current write position.
        ///
        /// @param b the `byte` to write
        /// @throws BufferOverflowException if there is no space left
        void put(byte b);

        /// Writes a `byte` at the specified write position.
        ///
        /// @param position the write position
        /// @param b the `byte` to write
        /// @throws BufferOverflowException if there is no space left
        void put(long position, byte b);

        /// Writes a `short` at the current write position.
        ///
        /// @param s the `short` to write
        /// @throws BufferOverflowException if there is no space left
        void putShort(short s);

        /// Writes a `short` at the specified write position.
        ///
        /// @param position the write position
        /// @param s the `short` to write
        /// @throws BufferOverflowException if there is no space left
        void putShort(long position, short s);

        /// Writes an `int` at the current write position.
        ///
        /// @param i the `int` to write
        /// @throws BufferOverflowException if there is no space left
        void putInt(int i);

        /// Writes an `int` at the specified write position.
        ///
        /// @param position the write position
        /// @param i the `int` to write
        /// @throws BufferOverflowException if there is no space left
        void putInt(long position, int i);

        /// Writes a `long` at the current write position.
        ///
        /// @param l the `long` to write
        /// @throws BufferOverflowException if there is no space left
        void putLong(long l);

        /// Writes a `long` at the specified write position.
        ///
        /// @param position the write position
        /// @param l the `long` to write
        /// @throws BufferOverflowException if there is no space left
        void putLong(long position, long l);

        /// Writes the given byte array at the current write position.
        ///
        /// @param bytes the byte array to write
        /// @throws BufferOverflowException if there is no space left
        default void put(byte[] bytes)
        {
            put(bytes, 0, bytes.length);
        }

        /// Writes the given byte array at the specified write position.
        ///
        /// @param bytes the byte array to write
        /// @param offset the offset within the byte array of the first byte to be read
        /// @param length the number of bytes to be read from the byte array
        /// @throws BufferOverflowException if there is no space left
        void put(byte[] bytes, int offset, int length);

        /// Writes the given [ByteBuffer] at the current write position.
        ///
        /// @param byteBuffer the [ByteBuffer] to write
        /// @throws BufferOverflowException if there is no space left
        void put(ByteBuffer byteBuffer);

        /// Writes the given [RetainableByteBuffer] at the current write position.
        ///
        /// @param buffer the [RetainableByteBuffer] to write
        /// @throws BufferOverflowException if there is no space left
        void put(RetainableByteBuffer buffer);

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

        /// Compacts this buffer.
        ///
        /// Unread bytes, if any, are moved to position `0`.
        void compact();

        /// Clears this buffer.
        ///
        /// Unread bytes are discarded.
        void clear();

        /// Writes into this buffer with the given [Writer].
        ///
        /// @param writer the [Writer] that writes into this buffer
        /// @return the number of bytes written, or a negative value returned by the [Writer]
        /// @throws IOException when the write fails
        long write(Writer writer) throws IOException;

        /// Wraps [#write(Writer)] so that [IOException]s are wrapped in [UncheckedIOException]s.
        ///
        /// @param writer the [Writer] that writes into this buffer
        /// @return the number of bytes written, or a negative value returned by the [Writer]
        default long quietWrite(Writer writer)
        {
            try
            {
                return write(writer);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }

        /// Base interface to write into this buffer via its internal [ByteBuffer].
        ///
        /// This interface is used to bridge this buffer to APIs that work with [ByteBuffer],
        /// such as [ByteChannel#read(ByteBuffer)].
        interface Writer
        {
            /// Implementations write bytes into the given [ByteBuffer].
            ///
            /// This method should return the number of bytes written to the [ByteBuffer],
            /// but also negative values to indicate some external failure when trying
            /// to write bytes into this buffer.
            ///
            /// @param output the [ByteBuffer] to write into
            /// @return the number of bytes written, or a negative value
            /// @throws IOException when the write fails
            long writeTo(ByteBuffer output) throws IOException;
        }
    }
}
