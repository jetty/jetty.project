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
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.internal.AccumulatingReadBuffer;
import org.eclipse.jetty.util.internal.FixedSizeBuffer;

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
    ReadableBuffer EMPTY = new FixedSizeBuffer.ReadOnly(ByteBuffer.allocate(0).flip(), Retainable.NON_RETAINABLE);

    /**
     * Wraps the given NIO ByteBuffer that already is in flush node, using a new {@link ReferenceCounter} for retainability.
     * @param byteBuffer the NIO byte buffer
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(ByteBuffer byteBuffer)
    {
        return byteBuffer == null ? EMPTY : wrap(byteBuffer, new ReferenceCounter());
    }

    /**
     * Wraps the given NIO ByteBuffer that already is in flush node, using the provided {@link ReferenceCounter} for retainability.
     * @param byteBuffer the NIO byte buffer
     * @param retainable the retainable used for retainability of the NIO ByteBuffer
     * @return a ReadableBuffer
     */
    static ReadableBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new FixedSizeBuffer(byteBuffer, retainable, false);
    }

    static ReadableBuffer wrap(ByteBuffer... buffers)
    {
        if (BufferUtil.isEmpty(buffers))
            return EMPTY;
        if (buffers.length == 1)
            return wrap(buffers[0]);
        List<ReadableBuffer> rbs = Arrays.stream(buffers).map(ReadableBuffer::wrap).toList();
        return new AccumulatingReadBuffer(rbs);
    }

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
        return new AccumulatingReadBuffer(readableBuffers);
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

    byte get(long index);

    /**
     * Reads a single byte at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than one.
     */
    byte get();

    /// @return a single byte at the current position, converted to `int` via `get() & 0xFF`
    /// @throws BufferUnderflowException if the buffer's {@link #remaining()} is less than one.
    /// @see #get()
    default int getAsInt()
    {
        return get() & 0xFF;
    }

    /**
     * Reads a short at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than two.
     */
    short getShort();

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
     * Reads a long at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than eight.
     */
    long getLong();

    /**
     * Reads a byte array at the current position.
     * @throws BufferUnderflowException – If the buffer's {@link #remaining()} is less than the array's length.
     */
    void get(byte[] b);

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
     * // TODO throw ISE when isRetained() == true?
     * // TODO should toWritable() always compact, or take a boolean instead of this?
     * // TODO should this leave the buffer in readable mode and return void? It feels like compacting should only ever be done right before flipping to write mode.
     */
    WritableBuffer compact();

    /**
     * Drains and drops all unread bytes from this ReadableBuffer and resets the position to 0.
     * // TODO throw ISE when isRetained() == true?
     * // TODO is this method really useful? shouldn't it be removed?
     */
    void drain();

    /**
     * Flips this WritableBuffer to fill mode
     * @return this, typed as a {@link ReadableBuffer}
     * // TODO throw ISE when isRetained() == true?
     * // TODO should this auto-compact when empty but not at position 0? Or always auto-compact?
     */
    WritableBuffer toWritable();

    String asString(Charset charset);

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
         * is depleted, for instance if the WritableBuffer is backed by more than one NIO ByteBuffer.
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
         * Flushes a given NIO ByteBuffer. Note that this method is never be called more than once.
         * @param inputs the buffer to be written
         * @throws IOException when IOException occurs
         */
        void write(ByteBuffer[] inputs) throws IOException;
    }
}
