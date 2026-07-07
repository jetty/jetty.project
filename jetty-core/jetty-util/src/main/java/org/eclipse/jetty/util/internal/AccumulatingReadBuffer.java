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
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class AccumulatingReadBuffer implements ReadableBuffer
{
    private final List<Long> originalBufferPositions;
    private final List<ReadableBuffer> originalBuffers;
    private final List<ReadableBuffer> readableBuffers;
    private final Retainable retainable;
    private final long capacity;
    private long position;

    public AccumulatingReadBuffer(List<ReadableBuffer> readableBuffers)
    {
        if (Objects.requireNonNull(readableBuffers).isEmpty())
            throw new IllegalArgumentException("Buffers list cannot be empty");

        this.retainable = new ReferenceCounter();
        this.readableBuffers = new ArrayList<>(readableBuffers.size());
        this.originalBuffers = new ArrayList<>(readableBuffers.size());
        this.originalBufferPositions = new ArrayList<>(readableBuffers.size());
        this.capacity = fillLists(readableBuffers);
        this.position = 0L;
    }

    private long fillLists(List<ReadableBuffer> buffers)
    {
        long totalCapacity = 0L;
        for (ReadableBuffer readableBuffer : buffers)
        {
            if (readableBuffer instanceof AccumulatingReadBuffer arb)
            {
                // Flatten the AccumulatingReadBuffers.
                totalCapacity += fillLists(arb.readableBuffers);
            }
            else
            {
                this.originalBuffers.add(readableBuffer);
                this.originalBufferPositions.add(readableBuffer.position());
                readableBuffer = readableBuffer.slice();
                totalCapacity += readableBuffer.capacity();
                this.readableBuffers.add(readableBuffer);
            }
        }
        return totalCapacity;
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
        for (int i = 0; i < readableBuffers.size(); i++)
        {
            ReadableBuffer currentRb = readableBuffers.get(i);
            ReadableBuffer originalRb = originalBuffers.get(i);
            Long originalRbPosition = originalBufferPositions.get(i);

            long currentLimit = currentRb.capacity();
            long nextLimit = Math.min(newPosition, currentLimit);
            currentRb.position(nextLimit);
            originalRb.position(originalRbPosition + nextLimit);
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

    private void consumeOriginalBuffers(long byteCount)
    {
        for (ReadableBuffer originalBuffer : originalBuffers)
        {
            if (originalBuffer.remaining() == 0L)
                continue;
            if (originalBuffer.remaining() >= byteCount)
            {
                originalBuffer.position(originalBuffer.position() + byteCount);
                break;
            }
            else
            {
                long remaining = originalBuffer.remaining();
                originalBuffer.position(originalBuffer.position() + remaining);
                byteCount -= remaining;
            }
            if (byteCount == 0)
                break;
        }
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
        consumeOriginalBuffers(1);
        return readableBuffer.get();
    }

    @Override
    public short getShort()
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= 2L)
        {
            consumeOriginalBuffers(2);
            position += 2L;
            return currentRb.getShort();
        }
        short aShort = fragmentedGet(currentRb, 2).getShort();
        consumeOriginalBuffers(2);
        return aShort;
    }

    @Override
    public int getShort(long index)
    {
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            long limit = readableBuffer.capacity();
            if (limit > index)
            {
                if (readableBuffer.remaining() >= 2L)
                    return readableBuffer.getShort();
                else
                    return fragmentedGet(readableBuffer, 2).getShort();
            }
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public int getInt()
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= 4L)
        {
            consumeOriginalBuffers(4);
            position += 4L;
            return currentRb.getInt();
        }
        int anInt = fragmentedGet(currentRb, 4).getInt();
        consumeOriginalBuffers(4);
        return anInt;
    }

    @Override
    public int getInt(long index)
    {
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            long limit = readableBuffer.capacity();
            if (limit > index)
            {
                if (readableBuffer.remaining() >= 4L)
                    return readableBuffer.getInt();
                else
                    return fragmentedGet(readableBuffer, 4).getInt();
            }
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public long getLong()
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= 8L)
        {
            consumeOriginalBuffers(8);
            position += 8L;
            return currentRb.getLong();
        }
        long aLong = fragmentedGet(currentRb, 8).getLong();
        consumeOriginalBuffers(8);
        return aLong;
    }

    @Override
    public long getLong(long index)
    {
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            long limit = readableBuffer.capacity();
            if (limit > index)
            {
                if (readableBuffer.remaining() >= 8L)
                    return readableBuffer.getLong();
                else
                    return fragmentedGet(readableBuffer, 8).getLong();
            }
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public void get(byte[] b)
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= b.length)
        {
            consumeOriginalBuffers(b.length);
            position += b.length;
            currentRb.get(b);
            return;
        }
        fragmentedGet(currentRb, b.length).get(b);
        consumeOriginalBuffers(b.length);
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        ReadableBuffer currentRb = currentReadableBuffer();
        if (currentRb.remaining() >= len)
        {
            consumeOriginalBuffers(len);
            position += b.length;
            currentRb.get(b, off, len);
            return;
        }
        fragmentedGet(currentRb, len).get(b, off, len);
        consumeOriginalBuffers(len);
    }

    @Override
    public ReadableBuffer slice()
    {
        List<ReadableBuffer> copy = new ArrayList<>(readableBuffers.size());
        for (ReadableBuffer readableBuffer : readableBuffers)
        {
            ReadableBuffer slice = readableBuffer.slice();
            copy.add(slice);
        }
        ReadableBuffer result = new AccumulatingReadBuffer(copy);
        copy.forEach(Retainable::release);
        return result;
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

        if (length == 0)
            return EMPTY;

        List<ReadableBuffer> copy = new ArrayList<>(readableBuffers.size());

        int i;
        long seekPosition = position;
        // First, skip buffers up to position.
        for (i = 0; i < readableBuffers.size(); i++)
        {
            ReadableBuffer readableBuffer = readableBuffers.get(i);
            long limit = readableBuffer.capacity();

            if (seekPosition < limit)
                break;
            seekPosition -= limit;
        }
        // Second, slice the remaining buffers up to length.
        for (; i < readableBuffers.size(); i++)
        {
            ReadableBuffer readableBuffer = readableBuffers.get(i);
            long subSlicePosition = readableBuffer.capacity() - (readableBuffer.capacity() - seekPosition);
            long subSliceLength;
            seekPosition = 0L;

            long remaining = readableBuffer.capacity() - subSlicePosition;
            if (length > remaining)
                subSliceLength = remaining;
            else
                subSliceLength = length;
            length -= remaining;

            ReadableBuffer slice = readableBuffer.slice(subSlicePosition, subSliceLength);
            copy.add(slice);

            if (length <= 0L)
            {
                ReadableBuffer result = new AccumulatingReadBuffer(copy);
                copy.forEach(Retainable::release);
                return result;
            }
        }
        throw new IllegalStateException("Should not happen");
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
        boolean writeCalled = false;
        long totalWritten = 0L;
        for (int i = 0; i < readableBuffers.size(); i++)
        {
            ReadableBuffer readableBuffer = readableBuffers.get(i);
            long remainingBefore = readableBuffer.remaining();
            if (remainingBefore == 0L)
                continue;

            if (target instanceof GatheringTarget gatheringTarget)
            {
                long totalRemainingBefore = remaining();
                List<ByteBuffer> buffers = gatherBuffers(i);
                int gathered = buffers.size();
                if (gathered > 1)
                {
                    i += gathered - 1;
                    writeCalled = true;
                    gatheringTarget.write(buffers.toArray(new ByteBuffer[0]));
                    long written = totalRemainingBefore - remaining();
                    position += written;
                    totalWritten += written;
                    continue;
                }
            }

            long positionBefore = readableBuffer.position();
            writeCalled = true;
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
        // Call Target.write() with an empty NIO buffer when this buffer is empty.
        if (!writeCalled)
            target.write(BufferUtil.EMPTY_BUFFER);
        else
            consumeOriginalBuffers(totalWritten);
        return totalWritten;
    }

    private List<ByteBuffer> gatherBuffers(int index)
    {
        List<ByteBuffer> buffers = null;
        for (int i = index; i < readableBuffers.size(); i++)
        {
            ReadableBuffer readableBuffer = readableBuffers.get(i);
            if (readableBuffer instanceof FixedSizeBuffer fixedSizeBuffer)
            {
                ByteBuffer buffer = fixedSizeBuffer.getByteBuffer();
                if (buffers == null)
                    buffers = new ArrayList<>();
                buffers.add(buffer);
            }
            else
            {
                break;
            }
        }
        return buffers == null ? List.of() : buffers;
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
            originalBuffers.clear();
            originalBufferPositions.clear();
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
