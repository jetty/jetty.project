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

package org.eclipse.jetty.util.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;

public class PathBuffer implements RetainableByteBuffer
{
    private final Retainable retainable;
    private final Path path;
    private final WritableBufferPool.Sized bufferPool;
    private final long offset;
    private final long size;
    private long position;

    public PathBuffer(Path path, long offset, long size, WritableBufferPool.Sized bufferPool) throws IOException
    {
        this(path, offset, size, bufferPool, new ReferenceCounter());
    }

    private PathBuffer(Path path, long offset, long size, WritableBufferPool.Sized bufferPool, Retainable retainable) throws IOException
    {
        this.path = Objects.requireNonNull(path);
        this.retainable = Objects.requireNonNull(retainable);
        this.bufferPool = Objects.requireNonNull(bufferPool);
        long fileSize = Files.size(path);
        this.size = size < 0L ? fileSize - offset : size;
        this.offset = offset;
        if (offset < 0L)
            throw new IllegalArgumentException("offset " + offset + " < 0 for file " + path);
        if (offset > fileSize)
            throw new IllegalArgumentException("offset " + offset + " > file size " + fileSize + " for file " + path);
        long offsetEnd = this.offset + this.size;
        if (offsetEnd > fileSize)
            throw new IllegalArgumentException("offset + size " + offsetEnd + " > file size " + fileSize + " for file " + path);
    }

    private RetainableByteBuffer getBytesAt(long position, boolean absolute, int length)
    {
        if (position + length > size)
            throw new BufferUnderflowException();

        Mutable buffer = bufferPool.acquire(length);
        buffer.quietReadFrom(output ->
        {
            output.limit(length);
            try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
            {
                fileChannel.position(offset + position);
                int read = fileChannel.read(output);
                if (read != length)
                    throw new BufferUnderflowException();
                if (!absolute)
                    this.position += read;
                return read;
            }
        });
        return buffer;
    }

    @Override
    public long readPosition()
    {
        return position;
    }

    @Override
    public RetainableByteBuffer readPosition(long newPosition)
    {
        if (newPosition < 0L)
            throw new IllegalArgumentException("newPosition < 0");
        if (newPosition > size)
            throw new BufferUnderflowException();
        this.position = newPosition;
        return this;
    }

    @Override
    public long capacity()
    {
        return size;
    }

    @Override
    public long remaining()
    {
        return capacity() - readPosition();
    }

    @Override
    public byte get(long index)
    {
        RetainableByteBuffer buffer = getBytesAt(index, true, Byte.BYTES);
        byte b = buffer.get();
        buffer.release();
        return b;
    }

    @Override
    public byte get()
    {
        RetainableByteBuffer buffer = getBytesAt(position, false, Byte.BYTES);
        byte b = buffer.get();
        buffer.release();
        return b;
    }

    @Override
    public short getShort()
    {
        RetainableByteBuffer buffer = getBytesAt(position, false, Short.BYTES);
        short s = buffer.getShort();
        buffer.release();
        return s;
    }

    @Override
    public short getShort(long index)
    {
        RetainableByteBuffer buffer = getBytesAt(index, true, Short.BYTES);
        short s = buffer.getShort();
        buffer.release();
        return s;
    }

    @Override
    public int getInt()
    {
        RetainableByteBuffer buffer = getBytesAt(position, false, Integer.BYTES);
        int i = buffer.getInt();
        buffer.release();
        return i;
    }

    @Override
    public int getInt(long index)
    {
        RetainableByteBuffer buffer = getBytesAt(index, true, Integer.BYTES);
        int i = buffer.getInt();
        buffer.release();
        return i;
    }

    @Override
    public long getLong()
    {
        RetainableByteBuffer buffer = getBytesAt(position, false, Long.BYTES);
        long l = buffer.getLong();
        buffer.release();
        return l;
    }

    @Override
    public long getLong(long index)
    {
        RetainableByteBuffer buffer = getBytesAt(index, true, Long.BYTES);
        long l = buffer.getLong();
        buffer.release();
        return l;
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        RetainableByteBuffer buffer = getBytesAt(position, false, len);
        buffer.get(b, off, len);
        buffer.release();
    }

    @Override
    public void get(long index, byte[] b, int off, int len)
    {
        RetainableByteBuffer buffer = getBytesAt(index, true, len);
        buffer.get(b, off, len);
        buffer.release();
    }

    @Override
    public RetainableByteBuffer slice()
    {
        return slice(readPosition(), remaining());
    }

    @Override
    public RetainableByteBuffer slice(long position, long length)
    {
        if (position + length > capacity())
            throw new IllegalArgumentException("slice outside bounds: this[%d,%d] - slice[%s,%s]".formatted(
                readPosition(), capacity(), position, length
            ));
        try
        {
            retain();
            return new PathBuffer(path, offset + position, length, bufferPool, this);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        if (target instanceof TransferringTarget transferringTarget)
        {
            try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
            {
                long transferred = transferringTarget.write(fileChannel, offset + position, remaining());
                position += transferred;
                return transferred;
            }
        }

        RetainableByteBuffer.Mutable fileBuffer = bufferPool.acquire();
        try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
        {
            fileChannel.position(offset + position);
            long totalWritten = 0L;
            while (true)
            {
                long read = fileBuffer.readFrom(b ->
                {
                    long remaining = remaining();
                    if (remaining == 0)
                        return 0;

                    // We may be reading only a slice of the file.
                    // Clamp the buffer to avoid reading past the size.
                    int space = b.remaining();
                    if (space > remaining)
                        b.limit(b.position() + (int)remaining);

                    return fileChannel.read(b);
                });

                // Should not happen unless the file is modified concurrently.
                if (read < 0)
                    throw new EOFException();

                long written = fileBuffer.writeTo(target);

                totalWritten += written;
                position += written;

                // Target could not consume all the bytes, bail out.
                if (written < read)
                    break;

                // File was fully read.
                if (!hasRemaining())
                    break;

                fileBuffer.clear();
            }
            return totalWritten;
        }
        finally
        {
            fileBuffer.release();
        }
    }

    @Override
    public boolean canRetain()
    {
        return retainable.canRetain();
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
        return retainable.release();
    }

    @Override
    public int getRetained()
    {
        return retainable.getRetained();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{path=%s,p=%d,l=%d,o=%d,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            path,
            position,
            size,
            offset,
            retainable);
    }
}
