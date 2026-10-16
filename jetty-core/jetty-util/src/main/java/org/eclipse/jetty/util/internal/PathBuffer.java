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
    private final Path path;
    private final WritableBufferPool.Sized bufferPool;
    private final Retainable retainable;
    private final long offset;
    private final long size;
    private long position;
    private RetainableByteBuffer readBuffer;

    public PathBuffer(Path path, long offset, long size, WritableBufferPool.Sized bufferPool, Retainable retainable) throws IOException
    {
        if (offset < 0L)
            throw new IllegalArgumentException("offset " + offset + " < 0 for file " + path);
        long fileSize = Files.size(path);
        if (offset > fileSize)
            throw new IllegalArgumentException("offset " + offset + " > file size " + fileSize + " for file " + path);
        long realSize = size < 0L ? fileSize - offset : size;
        long offsetEnd = offset + realSize;
        if (offsetEnd > fileSize)
            throw new IllegalArgumentException("offset + size " + offsetEnd + " > file size " + fileSize + " for file " + path);
        this.path = path;
        this.bufferPool = Objects.requireNonNull(bufferPool);
        this.retainable = Objects.requireNonNull(retainable);
        this.size = realSize;
        this.offset = offset;
        retain();
    }

    private RetainableByteBuffer getBytesAt(long position, boolean absolute, int length)
    {
        if (position + length > size)
            throw new BufferUnderflowException();

        Mutable buffer = bufferPool.acquire(length);
        buffer.quietWrite(output ->
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
        // Retain this PathBuffer once; the slice has a different
        // reference counter from its parent that pairs this retain.
        retain();
        ReferenceCounter refCount = new ReferenceCounter()
        {
            @Override
            public boolean release()
            {
                boolean released = super.release();
                if (released)
                    PathBuffer.this.release();
                return released;
            }
        };
        try (refCount)
        {
            return new PathBuffer(path, offset + position, length, bufferPool, refCount);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long read(Reader reader) throws IOException
    {
        if (reader instanceof TransferringReader transferringReader)
        {
            try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
            {
                long transferred = transferringReader.readFrom(fileChannel, offset + position, remaining());
                position += transferred;
                return transferred;
            }
        }

        readBuffer = Retainable.dispose(readBuffer);

        try (Mutable fileBuffer = bufferPool.acquire())
        {
            try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
            {
                fileChannel.position(offset + position);

                long read = 0;
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

                        return fileChannel.read(b);
                    });

                    // Should not happen unless the file is modified concurrently.
                    if (written < 0)
                        throw new EOFException();

                    if (written == 0)
                        break;

                    read = fileBuffer.read(reader);

                    if (read > 0)
                    {
                        totalRead += read;
                        position += read;
                    }

                    // The Reader could not consume all the bytes, bail out.
                    if (read < written)
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

                return read < 0 ? read : totalRead;
            }
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
            readBuffer = Retainable.dispose(readBuffer);
        return released;
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
