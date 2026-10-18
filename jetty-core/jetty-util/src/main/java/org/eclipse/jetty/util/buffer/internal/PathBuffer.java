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

package org.eclipse.jetty.util.buffer.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// A [RetainableByteBuffer] backed by a region of a file.
///
/// The file is opened when this buffer is created, and the
/// [FileChannel] is shared with the slices of this buffer.
/// The [FileChannel] is closed when this buffer is fully released,
/// which happens only after all its slices have been released.
/// Reads use the positional [FileChannel] APIs, so that the
/// [FileChannel] can be used concurrently by this buffer and its slices.
public class PathBuffer implements RetainableByteBuffer
{
    private final Path path;
    private final FileChannel fileChannel;
    private final PathBuffer parent;
    private final ByteBufferPool.Sized bufferPool;
    private final Retainable retainable;
    private final long offset;
    private final long capacity;
    private long position;
    private RetainableByteBuffer readBuffer;

    public PathBuffer(Path path, long offset, long size, ByteBufferPool.Sized bufferPool)
    {
        if (offset < 0L)
            throw new IllegalArgumentException("offset " + offset + " < 0 for file " + path);

        FileChannel fileChannel = open(path);

        long capacity;
        try
        {
            long fileSize = fileSize(fileChannel);
            if (offset > fileSize)
                throw new IllegalArgumentException("offset " + offset + " > file size " + fileSize + " for file " + path);
            capacity = size < 0L ? fileSize - offset : size;
            long offsetEnd = offset + capacity;
            if (offsetEnd > fileSize)
                throw new IllegalArgumentException("offset + size " + offsetEnd + " > file size " + fileSize + " for file " + path);
        }
        catch (Throwable x)
        {
            IO.close(fileChannel);
            throw x;
        }

        this.path = path;
        this.fileChannel = fileChannel;
        this.parent = null;
        this.bufferPool = Objects.requireNonNull(bufferPool);
        this.retainable = new ReferenceCounter();
        this.offset = offset;
        this.capacity = capacity;
    }

    private PathBuffer(PathBuffer parent, long offset, long capacity)
    {
        this.path = parent.path;
        this.fileChannel = parent.fileChannel;
        this.parent = parent;
        this.bufferPool = parent.bufferPool;
        this.retainable = new ReferenceCounter();
        this.offset = offset;
        this.capacity = capacity;
        // The slice has its own reference counter, and
        // releases the parent when it is fully released.
        parent.retain();
    }

    private static FileChannel open(Path path)
    {
        try
        {
            return FileChannel.open(path, StandardOpenOption.READ);
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    private static long fileSize(FileChannel fileChannel)
    {
        try
        {
            return fileChannel.size();
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    private RetainableByteBuffer getBytesAt(long position, boolean absolute, int count)
    {
        // Do not read before the start of a slice.
        if (position < 0L)
            throw new IndexOutOfBoundsException("position " + position + " < 0");
        if (position + count > capacity)
            throw new BufferUnderflowException();
        try (Mutable buffer = bufferPool.acquire(count))
        {
            buffer.quietWrite(output ->
            {
                output.limit(output.position() + count);
                int total = 0;
                while (total < count)
                {
                    int read = fileChannel.read(output, offset + position + total);
                    // Should not happen unless the file is modified concurrently.
                    if (read < 0)
                        throw new BufferUnderflowException();
                    total += read;
                }
                if (!absolute)
                    this.position += total;
                return total;
            });
            buffer.retain();
            return buffer;
        }
    }

    @Override
    public long readPosition()
    {
        return position;
    }

    @Override
    public void readPosition(long newPosition)
    {
        if (newPosition < 0L)
            throw new IllegalArgumentException("newPosition < 0");
        if (newPosition > capacity)
            throw new BufferUnderflowException();
        this.position = newPosition;
    }

    @Override
    public long capacity()
    {
        return capacity;
    }

    @Override
    public long remaining()
    {
        return capacity() - readPosition();
    }

    @Override
    public byte get(long position)
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, true, Byte.BYTES))
        {
            return buffer.get();
        }
    }

    @Override
    public byte get()
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, false, Byte.BYTES))
        {
            return buffer.get();
        }
    }

    @Override
    public short getShort()
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, false, Short.BYTES))
        {
            return buffer.getShort();
        }
    }

    @Override
    public short getShort(long position)
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, true, Short.BYTES))
        {
            return buffer.getShort();
        }
    }

    @Override
    public int getInt()
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, false, Integer.BYTES))
        {
            return buffer.getInt();
        }
    }

    @Override
    public int getInt(long position)
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, true, Integer.BYTES))
        {
            return buffer.getInt();
        }
    }

    @Override
    public long getLong()
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, false, Long.BYTES))
        {
            return buffer.getLong();
        }
    }

    @Override
    public long getLong(long position)
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, true, Long.BYTES))
        {
            return buffer.getLong();
        }
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, false, len))
        {
            buffer.get(b, off, len);
        }
    }

    @Override
    public void get(long position, byte[] b, int off, int len)
    {
        try (RetainableByteBuffer buffer = getBytesAt(position, true, len))
        {
            buffer.get(b, off, len);
        }
    }

    @Override
    public RetainableByteBuffer slice(long position, long length)
    {
        Objects.checkFromIndexSize(position, length, capacity());
        return new PathBuffer(this, offset + position, length);
    }

    @Override
    public long read(Reader reader) throws IOException
    {
        if (reader instanceof TransferringReader transferringReader)
        {
            long transferred = transferringReader.readFrom(fileChannel, offset + position, remaining());
            if (transferred > 0)
                position += transferred;
            return transferred;
        }

        readBuffer = Retainable.dispose(readBuffer);

        try (Mutable fileBuffer = bufferPool.acquire())
        {
            long currentRead;
            long totalRead = 0;
            while (true)
            {
                long written = fileBuffer.write(b ->
                {
                    long remaining = remaining();
                    if (remaining == 0)
                        return 0;

                    // We may be reading only a slice of the file.
                    // Clamp the buffer to avoid reading past the size.
                    int space = b.remaining();
                    if (space > remaining)
                        b.limit(b.position() + (int)remaining);

                    // All the bytes previously read from the file have been
                    // consumed by the Reader, so the file position is the
                    // offset plus the position of this buffer.
                    return fileChannel.read(b, offset + position);
                });

                // Should not happen unless the file is modified concurrently.
                if (written < 0)
                    throw new EOFException();

                currentRead = fileBuffer.read(reader);

                if (currentRead > 0)
                {
                    totalRead += currentRead;
                    position += currentRead;
                }

                // The Reader could not consume all the bytes, bail out.
                if (currentRead < written)
                {
                    // Store the buffer, as the Reader may
                    // use the ByteBuffer asynchronously.
                    fileBuffer.retain();
                    readBuffer = fileBuffer;
                    break;
                }

                // File was fully read.
                if (!hasRemaining())
                    break;

                fileBuffer.clear();
            }

            return currentRead < 0 ? currentRead : totalRead;
        }
    }

    @Override
    public boolean isRetained()
    {
        return retainable.isRetained();
    }

    @Override
    public void retain()
    {
        retainable.retain();
    }

    @Override
    public boolean release()
    {
        boolean released = retainable.release();
        if (released)
        {
            readBuffer = Retainable.dispose(readBuffer);
            // Slices retain their parent, so when the root
            // is released no slice is using the FileChannel.
            if (parent == null)
                IO.close(fileChannel);
            else
                parent.release();
        }
        return released;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{p=%d,c=%d,o=%d,r=%s,path=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            position,
            capacity,
            offset,
            retainable,
            path);
    }
}
