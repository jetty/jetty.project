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
        this.byteBuffer = byteBuffer;
        this.retainable = retainable;
        this.flushPosition = writeMode ? 0 : -1;
    }

    public ByteBuffer getByteBuffer()
    {
        return byteBuffer;
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
    public int getInt()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getInt();
    }

    @Override
    public long getLong()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot read from buffer in write mode");
        return byteBuffer.getLong();
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
            throw new IllegalStateException("Buffer is read-only");
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
    public void put(byte b)
    {
        if (flushPosition == -1)
            throw new IllegalStateException("Cannot write to buffer in read mode");
        byteBuffer.put(b);
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
    public void drain()
    {
        if (flushPosition != -1)
            throw new IllegalStateException("Cannot drain buffer in write mode");
        byteBuffer.position(0);
        byteBuffer.limit(0);
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
        return String.format("%s@%x{b=%s,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
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

    public static class WriteOnly extends FixedSizeBuffer
    {
        public WriteOnly(ByteBuffer byteBuffer, Retainable retainable)
        {
            super(byteBuffer, retainable, true);
        }

        @Override
        public ReadableBuffer toReadable()
        {
            throw new IllegalStateException("Write-only instance");
        }
    }

    public static class ReadOnly extends FixedSizeBuffer
    {
        public ReadOnly(ByteBuffer byteBuffer, Retainable retainable)
        {
            super(byteBuffer, retainable, false);
        }

        @Override
        public WritableBuffer toWritable()
        {
            throw new IllegalStateException("Read-only instance");
        }
    }
}
