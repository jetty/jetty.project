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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

/// Implementation of [RetainableByteBuffer.Mutable] with "auto-flip" features.
///
/// The invocation of "read" APIs will cause this instance to switch to "read mode", where:
/// * `flipPosition == -1`
/// * The data to read is between the wrapped [ByteBuffer] position and limit.
///
/// The invocation of "write" APIs will cause this instance to switch to "write mode", where:
/// * `flipPosition >= 0` holds the position of the data to read.
/// * The space to write is between the wrapped [ByteBuffer] position and limit.
public class SingleMutableBuffer implements RetainableByteBuffer.Mutable
{
    private final ByteBuffer byteBuffer;
    private final Retainable retainable;
    private int flipPosition;

    public SingleMutableBuffer(ByteBuffer byteBuffer, Retainable retainable, boolean writeMode)
    {
        this.byteBuffer = Objects.requireNonNull(byteBuffer);
        this.retainable = Objects.requireNonNull(retainable);
        this.flipPosition = writeMode ? 0 : -1;
    }

    ByteBuffer getByteBuffer()
    {
        return byteBuffer;
    }

    private ByteBuffer flipToRead()
    {
        if (flipPosition >= 0)
        {
            byteBuffer.limit(byteBuffer.position());
            byteBuffer.position(flipPosition);
            flipPosition = -1;
        }
        return byteBuffer;
    }

    private ByteBuffer flipToWrite()
    {
        if (flipPosition < 0)
        {
            flipPosition = byteBuffer.position();
            byteBuffer.position(byteBuffer.limit());
            byteBuffer.limit(byteBuffer.capacity());
        }
        return byteBuffer;
    }

    @Override
    public void byteOrder(ByteOrder byteOrder)
    {
        byteBuffer.order(byteOrder);
    }

    @Override
    public long readPosition()
    {
        return flipPosition < 0 ? byteBuffer.position() : flipPosition;
    }

    @Override
    public RetainableByteBuffer readPosition(long newPosition)
    {
        flipToRead().position(Math.toIntExact(newPosition));
        return this;
    }

    @Override
    public long writePosition()
    {
        return flipPosition < 0 ? byteBuffer.limit() : byteBuffer.position();
    }

    @Override
    public Mutable writePosition(long newPosition)
    {
        flipToWrite().position(Math.toIntExact(newPosition));
        return this;
    }

    @Override
    public Mutable pad(long length)
    {
        return pad((byte)0, length);
    }

    private Mutable pad(byte value, long length)
    {
        if (length < 0 || length > space())
            throw new IllegalArgumentException("invalid length " + length);
        ByteBuffer b = flipToWrite();
        // Copies the byte to pad into each of the 8 bytes of the long.
        long v = (value & 0xFF) * 0x0101010101010101L;
        long end = b.position() + length;
        for (long p = b.position(); p + Long.BYTES <= end; p += Long.BYTES)
        {
            b.putLong(v);
        }
        for (long p = b.position(); p < end; ++p)
        {
            b.put(value);
        }
        return this;
    }

    @Override
    public long capacity()
    {
        return byteBuffer.capacity();
    }

    @Override
    public long remaining()
    {
        return flipPosition < 0 ? byteBuffer.remaining() : byteBuffer.position() - flipPosition;
    }

    @Override
    public boolean hasRemaining()
    {
        return flipPosition < 0 ? byteBuffer.hasRemaining() : byteBuffer.position() > flipPosition;
    }

    @Override
    public long space()
    {
        return flipPosition < 0 ? byteBuffer.capacity() - byteBuffer.limit() : byteBuffer.remaining();
    }

    @Override
    public byte get()
    {
        return flipToRead().get();
    }

    @Override
    public byte get(long index)
    {
        return flipToRead().get(Math.toIntExact(index));
    }

    @Override
    public short getShort()
    {
        return flipToRead().getShort();
    }

    @Override
    public short getShort(long index)
    {
        return flipToRead().getShort(Math.toIntExact(index));
    }

    @Override
    public int getInt()
    {
        return flipToRead().getInt();
    }

    @Override
    public int getInt(long index)
    {
        return flipToRead().getInt(Math.toIntExact(index));
    }

    @Override
    public long getLong()
    {
        return flipToRead().getLong();
    }

    @Override
    public long getLong(long index)
    {
        return flipToRead().getLong(Math.toIntExact(index));
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        flipToRead().get(b, off, len);
    }

    @Override
    public void get(long index, byte[] b, int off, int len)
    {
        flipToRead().get(Math.toIntExact(index), b, off, len);
    }

    @Override
    public RetainableByteBuffer slice(long position, long length)
    {
        ByteBuffer slice = flipToRead().slice(Math.toIntExact(position), Math.toIntExact(length));
        retain();
        return new SingleMutableBuffer(slice, this, false);
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        return target.write(flipToRead());
    }

    @Override
    public Mutable put(byte b)
    {
        flipToWrite().put(b);
        return this;
    }

    @Override
    public Mutable put(long position, byte b)
    {
        flipToWrite().put(Math.toIntExact(position), b);
        return this;
    }

    @Override
    public Mutable putShort(short s)
    {
        flipToWrite().putShort(s);
        return this;
    }

    @Override
    public Mutable putShort(long position, short s)
    {
        flipToWrite().putShort(Math.toIntExact(position), s);
        return this;
    }

    @Override
    public Mutable putInt(int i)
    {
        flipToWrite().putInt(i);
        return this;
    }

    @Override
    public Mutable putInt(long position, int i)
    {
        flipToWrite().putInt(Math.toIntExact(position), i);
        return this;
    }

    @Override
    public Mutable putLong(long l)
    {
        flipToWrite().putLong(l);
        return this;
    }

    @Override
    public Mutable putLong(long position, long l)
    {
        flipToWrite().putLong(Math.toIntExact(position), l);
        return this;
    }

    @Override
    public Mutable put(byte[] src, int offset, int length)
    {
        flipToWrite().put(src, offset, length);
        return this;
    }

    @Override
    public Mutable put(ByteBuffer byteBuffer)
    {
        flipToWrite().put(byteBuffer);
        return this;
    }

    @Override
    public Mutable put(RetainableByteBuffer buffer)
    {
        if (buffer instanceof SingleMutableBuffer smb)
        {
            put(smb.flipToRead());
            return this;
        }

        buffer.quietWriteTo(src ->
        {
            long result = src.remaining();
            byteBuffer.put(src);
            return result;
        });

        return this;
    }

    @Override
    public long append(ByteBuffer byteBuffer)
    {
        int remaining = byteBuffer.remaining();
        long space = space();
        if (remaining <= space)
        {
            flipToWrite().put(byteBuffer);
            return remaining;
        }
        else
        {
            int limit = byteBuffer.limit();
            byteBuffer.limit(byteBuffer.position() + (int)space);
            flipToWrite().put(byteBuffer);
            byteBuffer.limit(limit);
            return space;
        }
    }

    @Override
    public long append(RetainableByteBuffer buffer)
    {
        long remaining = buffer.remaining();
        long space = space();
        if (remaining <= space)
        {
            put(buffer);
            return remaining;
        }
        else
        {
            RetainableByteBuffer slice = buffer.sliceAndConsume(space);
            put(slice);
            slice.release();
            return space;
        }
    }

    @Override
    public Mutable compact()
    {
        if (isRetained())
            throw new IllegalStateException("cannot compact retained buffer");
        // ByteBuffer.compact() leaves the ByteBuffer in write mode.
        flipToRead().compact();
        flipPosition = 0;
        return this;
    }

    @Override
    public Mutable clear()
    {
        flipToWrite().clear();
        flipPosition = 0;
        return this;
    }

    @Override
    public long readFrom(Fount fount) throws IOException
    {
        return fount.read(flipToWrite());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{fp=%d,b=%s,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            flipPosition,
            BufferUtil.toDetailString(byteBuffer, (int)readPosition(), (int)writePosition(), (int)remaining()),
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

    public static class Empty extends SingleMutableBuffer
    {
        public static final Empty INSTANCE = new Empty();

        private Empty()
        {
            super(BufferUtil.EMPTY_BUFFER, Retainable.NON_RETAINABLE, false);
        }
    }
}
