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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class FixedSizeBuffer implements WritableBuffer, ReadableBuffer
{
    private final ByteBuffer byteBuffer;
    private final Retainable retainable;
    private int flushPosition; // -1 when in read mode

    public FixedSizeBuffer(ByteBuffer byteBuffer, Retainable retainable, boolean writeMode)
    {
        this.byteBuffer = Objects.requireNonNull(byteBuffer);
        this.retainable = Objects.requireNonNull(retainable);
        this.flushPosition = writeMode ? 0 : -1;
    }

    public ByteBuffer getByteBuffer()
    {
        return byteBuffer;
    }

    public FixedSizeBuffer asReadOnly()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot access read-only buffer in write mode");
        if (byteBuffer.isReadOnly())
            return this;
        return new FixedSizeBuffer(byteBuffer.asReadOnlyBuffer(), retainable, false);
    }

    @Override
    public long position()
    {
        return byteBuffer.position();
    }

    @Override
    public void position(long newPosition)
    {
        byteBuffer.position(Math.toIntExact(newPosition));
    }

    @Override
    public long capacity()
    {
        return byteBuffer.capacity();
    }

    @Override
    public long remaining()
    {
        return byteBuffer.limit() - byteBuffer.position();
    }

    // Readable

    @Override
    public byte get(long index)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.get(Math.toIntExact(index));
    }

    @Override
    public byte get()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.get();
    }

    @Override
    public short getShort()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getShort();
    }

    @Override
    public int getShort(long index)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getShort(Math.toIntExact(index));
    }

    @Override
    public int getInt()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getInt();
    }

    @Override
    public int getInt(long index)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getInt(Math.toIntExact(index));
    }

    @Override
    public long getLong()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getLong();
    }

    @Override
    public long getLong(long index)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getLong(Math.toIntExact(index));
    }

    @Override
    public void get(byte[] b)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        byteBuffer.get(b);
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        byteBuffer.get(b, off, len);
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        int remainingBefore = byteBuffer.remaining();
        target.write(byteBuffer);
        return remainingBefore - byteBuffer.remaining();
    }

    @Override
    public WritableBuffer toWritable()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Buffer already in write mode");
        if (byteBuffer.isReadOnly())
            throw new ReadOnlyBufferException();
        // Always compact when there is nothing to copy.
        if (remaining() == 0L)
            byteBuffer.compact().flip();
        flushPosition = byteBuffer.position();
        byteBuffer.position(byteBuffer.limit());
        byteBuffer.limit(byteBuffer.capacity());
        return this;
    }

    @Override
    public ReadableBuffer slice()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot slice buffer in write mode");
        retainable.retain();
        return new FixedSizeBuffer(byteBuffer.slice(), retainable, false);
    }

    @Override
    public ReadableBuffer slice(long position, long length)
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot slice buffer in write mode");
        retainable.retain();
        return new FixedSizeBuffer(byteBuffer.slice(Math.toIntExact(position), Math.toIntExact(length)), retainable, false);
    }

    // Writable

    @Override
    public void byteOrder(boolean littleEndian)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot change byte order in read mode");
        byteBuffer.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    @Override
    public void put(byte b)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.put(b);
    }

    @Override
    public void put(long position, byte b)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.put(Math.toIntExact(position), b);
    }

    @Override
    public void put(byte[] src)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.put(src);
    }

    @Override
    public void put(byte[] src, int offset, int length)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.put(src, offset, length);
    }

    @Override
    public void put(ReadableBuffer readableBuffer)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        if (readableBuffer instanceof FixedSizeBuffer fsb)
        {
            byteBuffer.put(fsb.getByteBuffer());
            return;
        }
        try
        {
            readableBuffer.writeTo(byteBuffer::put);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void putShort(short s)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.putShort(s);
    }

    @Override
    public void putShort(long position, short s)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.putShort(Math.toIntExact(position), s);
    }

    @Override
    public void putInt(int i)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.putInt(i);
    }

    @Override
    public void putLong(long l)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.putLong(l);
    }

    @Override
    public void putBytes(byte[] bytes)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.put(bytes);
    }

    @Override
    public ReadableBuffer toReadable()
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Buffer already in read mode");
        byteBuffer.limit(byteBuffer.position());
        byteBuffer.position(Math.min(flushPosition, byteBuffer.position()));
        flushPosition = -1;
        return this;
    }

    @Override
    public WritableBuffer compact()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot compact buffer in write mode");
        byteBuffer.compact().flip();
        return toWritable();
    }

    @Override
    public long readFrom(Fount fount) throws IOException
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        int remainingBefore = byteBuffer.remaining();
        boolean eof = fount.read(byteBuffer);
        int read = remainingBefore - byteBuffer.remaining();
        return read == 0L && eof ? -1L : read;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{fp=%d,b=%s,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            flushPosition,
            BufferUtil.toDetailString(byteBuffer),
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
        return retainable.release();
    }

    @Override
    public int getRetained()
    {
        return retainable.getRetained();
    }

    public static class Empty extends FixedSizeBuffer
    {
        public Empty(boolean writeMode)
        {
            super(writeMode ? BufferUtil.EMPTY_BUFFER : BufferUtil.EMPTY_BUFFER.asReadOnlyBuffer(), Retainable.NON_RETAINABLE, writeMode);
        }

        @Override
        public ReadableBuffer toReadable()
        {
            if (!getByteBuffer().isReadOnly())
                throw new UnsupportedOperationException("Write-only");
            return super.toReadable();
        }

        @Override
        public WritableBuffer toWritable()
        {
            if (getByteBuffer().isReadOnly())
                throw new ReadOnlyBufferException();
            return super.toWritable();
        }
    }
}
