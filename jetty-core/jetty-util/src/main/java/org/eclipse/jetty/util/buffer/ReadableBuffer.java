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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.internal.AccumulatingReadBuffer;
import org.eclipse.jetty.util.internal.FixedSizeBuffer;
import org.eclipse.jetty.util.internal.PathReadableBuffer;
import org.eclipse.jetty.util.internal.ReadOnlyReadableBuffer;

/**
 * Wraps a byte container, exposing a read-only API. The byte container could be for instance:
 * <ul>
 *  <li>a single NIO ByteBuffer</li>
 *  <li>a list of NIO ByteBuffers</li>
 *  <li>a FileChannel</li>
 *  </ul>
 *  Note that {@link #toWritable()} can be called to access the write-only API if the byte container is not read-only.
 */
public interface ReadableBuffer extends Retainable
{
    /**
     * An empty ReadableBuffer that cannot be flipped to write-only mode.
     */
    ReadableBuffer EMPTY = new FixedSizeBuffer.Empty(false);

    /**
     * Wraps the given NIO ByteBuffer that is in flush mode, using a {@link ReferenceCounter} for retainability.
     * @param byteBuffer the NIO byte buffer
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(ByteBuffer byteBuffer)
    {
        return byteBuffer == null ? EMPTY : wrap(byteBuffer, new ReferenceCounter());
    }

    /**
     * Wraps the given byte array, using a {@link ReferenceCounter} for retainability.
     * @param bytes the byte array
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(byte[] bytes)
    {
        return bytes == null ? EMPTY : wrap(ByteBuffer.wrap(bytes));
    }

    /**
     * Wraps the given byte array, starting at the specified offset for the given length and using a {@link ReferenceCounter} for retainability.
     * @param bytes the byte array
     * @param offset the offset within the array of the first byte to be read
     * @param length the number of bytes to be read from the given array
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(byte[] bytes, int offset, int length)
    {
        return bytes == null ? EMPTY : wrap(ByteBuffer.wrap(bytes, offset, length));
    }

    /**
     * Wraps the given NIO ByteBuffer that is in flush mode, using the provided {@link ReferenceCounter} for retainability.
     * @param byteBuffer the NIO byte buffer
     * @param retainable the retainable used for retainability of the NIO ByteBuffer
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new FixedSizeBuffer(byteBuffer, retainable, false);
    }

    /**
     * Wraps the given NIO ByteBuffers that are in flush mode, using a {@link ReferenceCounter} for retainability.
     * @param buffers the NIO byte buffers
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(ByteBuffer... buffers)
    {
        if (BufferUtil.isEmpty(buffers))
            return EMPTY;
        if (buffers.length == 1)
            return wrap(buffers[0]);
        List<ReadableBuffer> rbs = Arrays.stream(buffers).map(ReadableBuffer::wrap).toList();
        return new AccumulatingReadBuffer(rbs);
    }

    // TODO this is only useful for tests. Remove?
    static ReadableBuffer allocate(int size, boolean direct)
    {
        WritableBuffer wb = WritableBuffer.allocate(size, direct);
        wb.position(size);
        return wb.toReadable();
    }

    /**
     * Wraps the given ReadableBuffer list, using a new {@link ReferenceCounter} for retainability.
     * @param readableBuffers the ReadableBuffer list
     * @return a ReadableBuffer
     */
    static ReadableBuffer accumulate(List<ReadableBuffer> readableBuffers)
    {
        if (readableBuffers.isEmpty())
            return EMPTY;
        // TODO duplicate the readable buffer instead of wrapping it into an accumulating one
        //  if it is the only one in the list?
        return new AccumulatingReadBuffer(readableBuffers);
    }

    /**
     * Wraps the given ReadableBuffer array, using a new {@link ReferenceCounter} for retainability.
     * @param readableBuffers the ReadableBuffer array
     * @return a ReadableBuffer
     */
    static ReadableBuffer accumulate(ReadableBuffer... readableBuffers)
    {
        List<ReadableBuffer> list = new ArrayList<>(readableBuffers.length);
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            // TODO do not add the readable buffer if it has 0 remaining bytes?
            if (readableBuffer != null)
                list.add(readableBuffer);
        }
        return accumulate(list);
    }

    /**
     * Wraps the given Path, using a new {@link ReferenceCounter} for retainability.
     * @param path the path to expose as a ReadableBuffer
     * @param pool the buffer pool to use for allocating buffers needed to read the file
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(Path path, WritableBufferPool.Sized pool) throws IOException
    {
        return new PathReadableBuffer(path, 0L, -1L, pool);
    }

    /**
     * Wraps the given Path, using a new {@link ReferenceCounter} for retainability.
     * @param path the path to expose as a ReadableBuffer
     * @param offset the offset within the file to start reading from
     * @param length the number of bytes to read from the file
     * @param pool the buffer pool to use for allocating buffers needed to read the file
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(Path path, long offset, long length, WritableBufferPool.Sized pool) throws IOException
    {
        if (length == 0L)
            return EMPTY;
        return new PathReadableBuffer(path, offset, length, pool);
    }

    // TODO this does not respect the retaining doctrine.
    static ReadableBuffer asReadOnly(ReadableBuffer readableBuffer)
    {
        return switch (readableBuffer)
        {
            case null -> null;
            case FixedSizeBuffer fixedSizeBuffer -> fixedSizeBuffer.asReadOnly();
            case ReadOnlyReadableBuffer readOnlyReadableBuffer -> readOnlyReadableBuffer;
            default -> new ReadOnlyReadableBuffer(readableBuffer);
        };
    }

    /**
     * Returns the current position of this ReadableBuffer, where the next bytes are to be read.
     * This value always lies between 0 and {@link #capacity()}.
     * @return the current position
     */
    long position();

    /**
     * Changes the current position of this ReadableBuffer, where the next bytes are to be read.
     * Must always be between 0 and {@link #capacity()}.
     * @param newPosition the new current position
     */
    void position(long newPosition);

    /**
     * Returns the capacity of this ReadableBuffer, in bytes.
     * @return the capacity of this ReadableBuffer
     */
    long capacity();

    /**
     * Returns how many spare bytes are left for reading, between {@link #position()} and the {@link WritableBuffer#position()}.
     * @return how many spare bytes are left for reading
     */
    long remaining();

    /**
     * Reads a byte at the specified absolute position.
     * @param index the absolute position of the byte to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than one.
     */
    byte get(long index);

    /**
     * Reads a single byte at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than one.
     */
    byte get();

    /**
     * Reads a single byte at the current position, converted to `int` via `get() &amp; 0xFF`
     * @throws BufferUnderflowException if the buffer's {@link #remaining()} is less than one.
     * @see #get()
     */
    default int getAsInt()
    {
        return get() & 0xFF;
    }

    /**
     * Reads a short at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than two.
     */
    short getShort();

    /**
     * Reads a short at the specified absolute position.
     * @param index the absolute position of the short to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than two.
     */
    int getShort(long index);

    /**
     * Reads a short at the current position, converted to `int` via `get() &amp; 0xFFFF`
     * @throws BufferUnderflowException if the buffer's {@link #remaining()} is less than two.
     * @see #get()
     */
    default int getShortAsInt()
    {
        return getShort() & 0xFFFF;
    }

    /**
     * Reads an int at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than four.
     */
    int getInt();

    /**
     * Reads an int at the specified absolute position.
     * @param index the absolute position of the int to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than four.
     */
    int getInt(long index);

    /**
     * Reads a long at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than eight.
     */
    long getLong();

    /**
     * Reads a long at the specified absolute position.
     * @param index the absolute position of the long to read
     * @throws BufferUnderflowException – If the buffer's remaining bytes at the given index is less than eight.
     */
    long getLong(long index);

    /**
     * Reads a byte array at the current position.
     * @param b the byte array to read into
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than the array's length.
     */
    void get(byte[] b);

    /**
     * Reads a byte array at the current position.
     * @param b the byte array to read into
     * @param off the offset within the array of the first byte to be read
     * @param len the number of bytes to be read from the given array
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than the array's length.
     */
    void get(byte[] b, int off, int len);

    /**
     * Slices this ReadableBuffer, {@link Retainable#retain() retaining} it in the process.
     * @return a new ReadableBuffer with a position of 0 that indexes the current ReadableBuffer's {@link #position()}
     * and an adjusted capacity equal to the current ReadableBuffer's {@link #capacity()} - the current
     * ReadableBuffer's {@link #position()}.
     */
    ReadableBuffer slice();

    /**
     * Slices this ReadableBuffer, {@link Retainable#retain() retaining} it in the process.
     * @param position the absolute position of the current buffer to use as the slice's position 0. Must be &lt; {@link #capacity()}.
     * @param length the length of the slice. Must be &lt; {@link #capacity()} - position - 1.
     * @return a new ReadableBuffer with a position of 0 that indexes the current ReadableBuffer's {@link #position()} + {@code position}
     * and an adjusted capacity equal to {@code length}.
     */
    ReadableBuffer slice(long position, long length);

    /**
     * Compacts this ReadableBuffer, by flipping it to a {@link WritableBuffer} with the unread bytes (between
     * {@link #position()} and {@link #remaining()}) moved to position 0.
     * @return this, typed as a {@link WritableBuffer}
     * TODO throw ISE when isRetained() == true?
     * TODO should this be moved to WritableBuffer?
     * TODO specify what this throws when the buffer is read-only
     */
    WritableBuffer compact();

    /**
     * Flips this WritableBuffer to fill mode
     * @return this, typed as a {@link ReadableBuffer}
     * TODO throw ISE when isRetained() == true?
     * TODO specify what this throws when the buffer is read-only
     */
    WritableBuffer toWritable();

    /**
     * Flushes this buffer to the given Target.
     * @param target the target
     * @return the # of bytes written
     * @throws IOException when an IOException occurs
     */
    long writeTo(Target target) throws IOException;

    /**
     * Base interface of the Target (i.e.: byte destination) used to flush a ReadableBuffer via the NIO ByteBuffer API.
     */
    interface Target
    {
        /**
         * Flushes a given NIO ByteBuffer. Note that this method can be called more than once if the {@code input} byte buffer
         * is depleted, for instance, if the WritableBuffer is backed by more than one NIO ByteBuffer.
         * @param input the buffer to be written
         * @throws IOException when IOException occurs
         */
        void write(ByteBuffer input) throws IOException;
    }

    /**
     * Interface of the Target (i.e.: byte destination) used to flush a ReadableBuffer via the NIO ByteBuffer API when the
     * target supports gathering writes.
     */
    interface GatheringTarget extends Target
    {
        /**
         * Flushes a given NIO ByteBuffer array.
         * @param inputs the buffer to be written
         * @throws IOException when IOException occurs
         */
        void write(ByteBuffer[] inputs) throws IOException;
    }

    /**
     * Interface of the Target (i.e.: byte destination) used to flush a ReadableBuffer backed by a FileChannel.
     * This is meant to be used when the target can perform the copy via NIO FileChannel.transferTo().
     */
    interface TransferringTarget extends Target
    {
        /**
         * Flushes a given FileChannel from the given position, up to the given count.
         * @param input the source FileChannel
         * @param position the position in the source FileChannel; always non-negative
         * @param count the maximum number of bytes to be transferred; always non-negative
         * @return the number of bytes that were transferred
         * @throws IOException when IOException occurs
         */
        long write(FileChannel input, long position, long count) throws IOException;
    }
}
