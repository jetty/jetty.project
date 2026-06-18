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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class AccumulatingReadBuffer implements ReadableBuffer
{
    private final List<ReadableBuffer> readableBuffers;
    private final Retainable retainable;
    private final long capacity;
    private long position;

    public AccumulatingReadBuffer(List<ReadableBuffer> readableBuffers)
    {
        if (readableBuffers.isEmpty())
            throw new IllegalArgumentException("Buffers list cannot be empty");
        this.retainable = new ReferenceCounter();
        long totalCapacity = 0L;
        this.readableBuffers = new ArrayList<>(readableBuffers.size());
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            if (readableBuffer.remaining() != readableBuffer.capacity())
                readableBuffer = readableBuffer.slice();
            else
                readableBuffer.retain();
            totalCapacity += readableBuffer.capacity();
            this.readableBuffers.add(readableBuffer);
        }
        this.capacity = totalCapacity;
        this.position = 0L;
    }

    @Override
    public long position()
    {
        return position;
    }

    @Override
    public void position(long newPosition)
    {
        if (newPosition > capacity)
            throw new IllegalArgumentException("newPosition(" + newPosition + ") > capacity(" + capacity + ")");
        this.position = newPosition;
        for (ReadableBuffer currentRb : readableBuffers)
        {
            long currentLimit = currentRb.capacity();
            long nextLimit = Math.min(newPosition, currentLimit);
            currentRb.position(nextLimit);
            newPosition -= currentLimit;
            newPosition = Math.max(0L, newPosition);
        }
    }

    @Override
    public long capacity()
    {
        return capacity;
    }

    @Override
    public long remaining()
    {
        long totalRemaining = 0L;
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            totalRemaining += readableBuffer.remaining();
        }
        return totalRemaining;
    }

    // Readable

    private ReadableBuffer currentReadableBuffer()
    {
        long currentPosition = position;
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            currentPosition -= readableBuffer.position();
            if (currentPosition <= 0L && readableBuffer.remaining() > 0L)
                return readableBuffer;
        }
        throw new BufferUnderflowException();
    }

    private ByteBuffer fragmentedGet(ReadableBuffer currentRb, int length)
    {
        if (remaining() < length)
            throw new BufferUnderflowException();
        ByteBuffer tmpBuf = ByteBuffer.allocate(length);
        position += currentRb.remaining();
        while (currentRb.remaining() > 0L)
        {
            tmpBuf.put(currentRb.get());
        }
        while (tmpBuf.hasRemaining())
        {
            currentRb = currentReadableBuffer();
            position += currentRb.position();
            position += currentRb.remaining();
            while (tmpBuf.hasRemaining() && currentRb.remaining() > 0L)
            {
                tmpBuf.put(currentRb.get());
            }
        }
        return tmpBuf.flip();
    }

    @Override
    public byte get(long index)
    {
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            long limit = readableBuffer.capacity();
            if (limit > index)
                return readableBuffer.get(index);
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public byte get()
    {
        ReadableBuffer readableBuffer = currentReadableBuffer();
        position++;
        return readableBuffer.get();
    }

    @Override
    public short getShort()
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= 2L)
        {
            position += 2L;
            return currentRb.getShort();
        }
        return fragmentedGet(currentRb, 2).getShort();
    }

    @Override
    public int getInt()
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= 4L)
        {
            position += 4L;
            return currentRb.getInt();
        }
        return fragmentedGet(currentRb, 4).getInt();
    }

    @Override
    public long getLong()
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= 8L)
        {
            position += 8L;
            return currentRb.getLong();
        }
        return fragmentedGet(currentRb, 8).getLong();
    }

    @Override
    public void get(byte[] b)
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= b.length)
        {
            position += b.length;
            currentRb.get(b);
            return;
        }
        fragmentedGet(currentRb, b.length).get(b);
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= len)
        {
            position += b.length;
            currentRb.get(b, off, len);
            return;
        }
        fragmentedGet(currentRb, len).get(b, off, len);
    }

    @Override
    public ReadableBuffer slice()
    {
        List<ReadableBuffer> copy = new ArrayList<>(readableBuffers.size());
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            copy.add(readableBuffer.slice());
            readableBuffer.release();
        }
        return new AccumulatingReadBuffer(copy);
    }

    @Override
    public ReadableBuffer slice(long position, long length)
    {
        if (position < 0)
            throw new IllegalArgumentException("position must be >= 0");
        if (length < 0)
            throw new IllegalArgumentException("length must be >= 0");
        if (position + length > capacity)
            throw new IllegalArgumentException("position(" + position + ") + length(" + length + ") must be <= capacity(" + capacity + ")");

        List<ReadableBuffer> copy = new ArrayList<>(readableBuffers.size());

        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            if (length == 0)
                break;

            long limit = readableBuffer.capacity();

            if (position >= limit)
            {
                position -= limit;
                continue;
            }

            long sliceLength = Math.min(readableBuffer.remaining(), length);
            ReadableBuffer slice = readableBuffer.slice(position, sliceLength);
            copy.add(slice);
            slice.release();
            length -= sliceLength;
        }

        return copy.isEmpty() ? EMPTY : new AccumulatingReadBuffer(copy);
    }

    @Override
    public WritableBuffer compact()
    {
        throw new IllegalStateException("Read-only instance");
    }

    @Override
    public void drain()
    {
        readableBuffers.forEach(Retainable::release);
        readableBuffers.clear();
    }

    @Override
    public WritableBuffer toWritable()
    {
        throw new IllegalStateException("Read-only instance");
    }

    @Override
    public String asString(Charset charset)
    {
        // TODO
        throw new UnsupportedOperationException();
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        if (target instanceof GatheringTarget gatheringTarget)
        {
            long totalRemainingBefore = remaining();
            List<ByteBuffer> buffers = new ArrayList<>();
            toByteBuffers(buffers);
            gatheringTarget.write(buffers.toArray(new ByteBuffer[0]));
            long totalWritten = totalRemainingBefore - remaining();
            position += totalWritten;
            return totalWritten;
        }

        long totalWritten = 0L;
        for (int i = 0; i < readableBuffers.size(); i++)
        {
            ReadableBuffer readableBuffer = readableBuffers.get(i);
            long remainingBefore = readableBuffer.remaining();
            if (remainingBefore == 0L)
                continue;
            long positionBefore = readableBuffer.position();
            readableBuffer.writeTo(target);
            long remainingAfter = readableBuffer.remaining();
            long written = remainingBefore - remainingAfter;
            position += written;
            if (i > 0)
                position += positionBefore;
            totalWritten += written;
            if (remainingAfter > 0L)
                break;
        }
        return totalWritten;
    }

    private void toByteBuffers(List<ByteBuffer> result)
    {
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            if (readableBuffer instanceof AccumulatingReadBuffer accumulatingReadBuffer)
                accumulatingReadBuffer.toByteBuffers(result);
            if (readableBuffer instanceof FixedSizeBuffer fixedSizeBuffer)
                result.add(fixedSizeBuffer.getByteBuffer());
            else
                throw new IllegalStateException("Unsupported ReadableBuffer type: " + readableBuffer.getClass().getName());
        }
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
            readableBuffers.forEach(Retainable::release);
            readableBuffers.clear();
        }
        return released;
    }

    @Override
    public int getRetained()
    {
        return retainable.getRetained();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{bs=%s,r=%s}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            readableBuffers,
            retainable);
    }
}
