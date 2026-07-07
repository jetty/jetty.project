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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;

public class PathReadableBuffer implements ReadableBuffer
{
    private final Retainable retainable;
    private final Path path;
    private final WritableBufferPool.Sized pool;
    private final long offset;
    private final long length;
    private long position;
    private ReadableBuffer writeToBuffer;

    public PathReadableBuffer(Path path, long offset, long length, WritableBufferPool.Sized pool) throws IOException
    {
        this(path, offset, length, pool, new ReferenceCounter());
    }

    private PathReadableBuffer(Path path, long offset, long length, WritableBufferPool.Sized pool, Retainable retainable) throws IOException
    {
        this.path = Objects.requireNonNull(path);
        this.retainable = Objects.requireNonNull(retainable);
        this.pool = Objects.requireNonNull(pool);
        long fileSize = Files.size(path);
        this.length = length < 0L ? fileSize : length;
        if (offset < 0L)
            throw new IllegalArgumentException("Offset " + offset + " < 0 for file " + path);
        if (offset > fileSize)
            throw new IllegalArgumentException("Offset " + offset + " > file size " + fileSize + " for file " + path);
        this.offset = offset;
    }

    private ReadableBuffer getLenAt(int len, long position, boolean absolute)
    {
        if (position + len > length)
            throw new BufferUnderflowException();

        WritableBuffer wb = pool.acquire(len);
        try
        {
            wb.readFrom(output ->
            {
                output.limit(len);
                try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
                {
                    fileChannel.position(offset + position);
                    int read = fileChannel.read(output);
                    if (read != len)
                        throw new BufferUnderflowException();
                    if (!absolute)
                        this.position += read;
                    return read == -1;
                }
            });
            return wb.toReadable();
        }
        catch (IOException e)
        {
            wb.release();
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public long position()
    {
        return position;
    }

    @Override
    public void position(long newPosition)
    {
        if (newPosition < 0L)
            throw new IllegalArgumentException("newPosition < 0");
        if (newPosition > length)
            throw new BufferUnderflowException();
        this.position = newPosition;
    }

    @Override
    public long capacity()
    {
        return length;
    }

    @Override
    public long remaining()
    {
        return capacity() - position();
    }

    @Override
    public byte get(long index)
    {
        ReadableBuffer rb = getLenAt(1, index, true);
        byte b = rb.get();
        rb.release();
        return b;
    }

    @Override
    public byte get()
    {
        ReadableBuffer rb = getLenAt(1, position, false);
        byte b = rb.get();
        rb.release();
        return b;
    }

    @Override
    public short getShort()
    {
        ReadableBuffer rb = getLenAt(2, position, false);
        short s = rb.getShort();
        rb.release();
        return s;
    }

    @Override
    public int getShort(long index)
    {
        ReadableBuffer rb = getLenAt(2, index, true);
        int s = rb.getShort();
        rb.release();
        return s;
    }

    @Override
    public int getInt()
    {
        ReadableBuffer rb = getLenAt(4, position, false);
        int i = rb.getInt();
        rb.release();
        return i;
    }

    @Override
    public int getInt(long index)
    {
        ReadableBuffer rb = getLenAt(4, index, true);
        int i = rb.getInt();
        rb.release();
        return i;
    }

    @Override
    public long getLong()
    {
        ReadableBuffer rb = getLenAt(8, position, false);
        long l = rb.getLong();
        rb.release();
        return l;
    }

    @Override
    public long getLong(long index)
    {
        ReadableBuffer rb = getLenAt(8, index, true);
        long l = rb.getLong();
        rb.release();
        return l;
    }

    @Override
    public void get(byte[] b)
    {
        ReadableBuffer rb = getLenAt(b.length, position, false);
        rb.get(b);
        rb.release();
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        ReadableBuffer rb = getLenAt(len, position, false);
        rb.get(b, off, len);
        rb.release();
    }

    @Override
    public ReadableBuffer slice()
    {
        try
        {
            return new PathReadableBuffer(path, offset + position, length - position, pool, new ReferenceCounter());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ReadableBuffer slice(long position, long length)
    {
        if (length > capacity())
            throw new IllegalArgumentException("length(" + length + ") > capacity(" + capacity() + ")");
        try
        {
            return new PathReadableBuffer(path, offset + position, length, pool, new ReferenceCounter());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public WritableBuffer compact()
    {
        throw new ReadOnlyBufferException();
    }

    @Override
    public WritableBuffer toWritable()
    {
        throw new ReadOnlyBufferException();
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

        WritableBuffer wb;
        if (writeToBuffer != null)
        {
            wb = writeToBuffer.toWritable();
            writeToBuffer = null;
        }
        else
        {
            wb = pool.acquire();
        }

        try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ))
        {
            fileChannel.position(offset);
            long totalWritten = 0L;
            while (true)
            {
                long read = wb.position();
                if (read == 0L)
                {
                    read = wb.readFrom(output ->
                    {
                        long remaining = remaining();
                        if (output.remaining() > remaining)
                            output.limit((int)remaining);
                        return fileChannel.read(output) == -1;
                    });
                    if (read < 1L)
                        break;
                }
                ReadableBuffer rb = wb.toReadable();
                long written = rb.writeTo(target);
                totalWritten += written;
                this.position += written;
                if (written != read)
                {
                    writeToBuffer = rb;
                    wb = null;
                    break;
                }
                if (remaining() == 0L)
                    break;
                rb.toWritable();
            }
            return totalWritten;
        }
        finally
        {
            if (wb != null)
                wb.release();
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{path=%s,p=%d,l=%d,o=%d,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            path,
            position,
            length,
            offset,
            retainable);
    }

    // Retainable

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
        boolean released = retainable.release();
        if (released)
        {
            if (writeToBuffer != null)
            {
                writeToBuffer.release();
                writeToBuffer = null;
            }
        }
        return released;
    }

    @Override
    public int getRetained()
    {
        return retainable.getRetained();
    }
}
