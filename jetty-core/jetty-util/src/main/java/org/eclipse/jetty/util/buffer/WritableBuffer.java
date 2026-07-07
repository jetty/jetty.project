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
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.internal.FixedSizeBuffer;

/**
 * Wraps a byte container, exposing a write-only API. The byte container could be for instance:
 * <ul>
 *  <li>a single NIO ByteBuffer</li>
 *  <li>a list of NIO ByteBuffers</li>
 *  <li>a FileChannel</li>
 *  </ul>
 *  Note that {@link #toReadable()} can be called to access the read-only API if the byte container is not write-only.
 */
public interface WritableBuffer
{
    /**
     * An empty WritableBuffer that cannot be flipped to read-only mode.
     */
    WritableBuffer EMPTY = FixedSizeBuffer.Empty.WRITE_ONLY_INSTANCE;

    /**
     * Wraps the given NIO ByteBuffer that already is in fill node, using a new {@link Retainable.ReferenceCounter} for
     * handling the release.
     * @param byteBuffer the NIO byte buffer
     * @return a WritableBuffer
     */
    static WritableBuffer wrap(ByteBuffer byteBuffer)
    {
        return new FixedSizeBuffer(byteBuffer, new Retainable.ReferenceCounter(), true);
    }

    /**
     * Wraps the given NIO ByteBuffer that already is in fill node.
     * @param byteBuffer the NIO byte buffer
     * @param retainable use the given {@link Retainable} for handling the release
     * @return a WritableBuffer
     */
    static WritableBuffer wrap(ByteBuffer byteBuffer, Retainable retainable)
    {
        return new FixedSizeBuffer(byteBuffer, retainable, true);
    }

    /**
     * Allocates a new WritableBuffer wrapping a NIO ByteBuffer in fill node, using a new {@link Retainable.ReferenceCounter} for
     * handling the release.
     * @param size the size of the buffer
     * @param direct true for a direct buffer, false for a heap one
     * @return a WritableBuffer
     */
    static WritableBuffer allocate(int size, boolean direct)
    {
        return new FixedSizeBuffer(direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size), new Retainable.ReferenceCounter(), true);
    }

    /**
     * Changes the byte order of this WritableBuffer.
     * @param littleEndian true for little endian, false for big endian
     */
    void byteOrder(boolean littleEndian);

    /**
     * Returns the current position of this WritableBuffer, where the next bytes are to be written.
     * This value always lies between 0 and {@link #capacity()}.
     * @return the current position
     */
    long position();

    /**
     * Changes the current position of this WritableBuffer, where the next bytes are to be written.
     * Must always be between 0 and {@link #capacity()}.
     * @param newPosition the new current position
     */
    void position(long newPosition);

    /**
     * Returns the capacity of this WritableBuffer, in bytes.
     * @return the capacity of this WritableBuffer
     */
    long capacity();

    /**
     * Returns how many spare bytes are left for writing, between {@link #position()} and {@link #capacity()}.
     * @return how many spare bytes are left for writing
     */
    long remaining();

    /**
     * Writes a single byte at the current position.
     * @param b the byte to write
     * @throws java.nio.BufferOverflowException if this buffer's current position is not smaller than its capacity
     */
    void put(byte b);

    /**
     * Writes a single byte at the given position.
     * @param position the position of the byte
     * @param b the byte to write
     * @throws java.nio.BufferOverflowException if this buffer's current position is not smaller than its capacity
     */
    void put(long position, byte b);

    /**
     * Writes the given source array at the current position.
     * @param src the array from which bytes are to be read
     */
    void put(byte[] src);

    /**
     * Writes the given source array at the current position.
     * @param src the array from which bytes are to be read
     * @param offset the offset within the array of the first byte to be read
     * @param length the number of bytes to be read from the given array
     */
    void put(byte[] src, int offset, int length);

    /**
     * Writes a {@link ReadableBuffer} at the current position.
     * @param readableBuffer the buffer to write
     * @throws java.nio.BufferOverflowException if there is insufficient space in this buffer for the remaining bytes in the source buffer     
     */
    void put(ReadableBuffer readableBuffer);

    /**
     * Writes a short at the current position.
     * @param s the short to write
     * @throws java.nio.BufferOverflowException if there are fewer than two bytes remaining in this buffer
     */
    void putShort(short s);

    /**
     * Writes a `short` at the given position.
     * @param position the position to write the `short`
     * @param s the `short` to write
     * @throws java.nio.BufferOverflowException if there are fewer than two bytes remaining in this buffer
     */
    void putShort(long position, short s);

    /**
     * Writes an int at the current position.
     * @param i the int to write
     * @throws java.nio.BufferOverflowException if there are fewer than four bytes remaining in this buffer     
     */
    void putInt(int i);

    /**
     * Writes a long at the current position.
     * @param l the long to write
     * @throws java.nio.BufferOverflowException if there are fewer than eight bytes remaining in this buffer
     */
    void putLong(long l);

    /**
     * Writes the given bytes at the current position.
     * @param bytes the bytes to write
     * @throws java.nio.BufferOverflowException if there are fewer than `bytes.length` remaining in this buffer
     */
    void putBytes(byte[] bytes);

    /**
     * Flips this WritableBuffer to flush mode
     * @return this, typed as a {@link ReadableBuffer}
     */
    ReadableBuffer toReadable();

    /**
     * <p>Releases this resource, potentially decrementing a reference count (if any).</p>
     *
     * @return {@code true} when the reference count goes to zero or if there was no reference count,
     *         {@code false} otherwise.
     * @see Retainable#release()
     */
    boolean release();

    /**
     * Fills this buffer with the given Fount.
     * @param fount the fount
     * @return the # of bytes read, or -1 if EOF was reached
     * @throws IOException when an IOException occurs
     */
    long readFrom(Fount fount) throws IOException;

    /**
     * Base interface of the Fount (i.e.: byte source) used to fill a WritableBuffer via the NIO ByteBuffer API.
     */
    interface Fount
    {
        /**
         * Fills a given NIO ByteBuffer. Note that this method can be called more than once if it does not
         * return true, for instance if the WritableBuffer is backed by more than one NIO ByteBuffer.
         * @param output the buffer to read into
         * @return true if EOF was reached while reading, false otherwise
         */
        boolean read(ByteBuffer output) throws IOException;
    }
}
