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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class MultiBuffer implements RetainableByteBuffer
{
    private final List<Long> originalPositions;
    private final List<RetainableByteBuffer> originalBuffers;
    private final List<RetainableByteBuffer> buffers;
    private final Retainable retainable;
    private final long capacity;
    private long position;

    public MultiBuffer(List<RetainableByteBuffer> buffers)
    {
        if (Objects.requireNonNull(buffers).isEmpty())
            throw new IllegalArgumentException("Buffers list cannot be empty");

        this.retainable = new ReferenceCounter();
        this.buffers = new ArrayList<>(buffers.size());
        this.originalBuffers = new ArrayList<>(buffers.size());
        this.originalPositions = new ArrayList<>(buffers.size());
        this.capacity = fillLists(buffers);
        this.position = 0L;
    }

    private long fillLists(List<RetainableByteBuffer> buffers)
    {
        long totalCapacity = 0L;
        for (RetainableByteBuffer buffer : buffers)
        {
            if (buffer instanceof MultiBuffer arb)
            {
                // Flatten nested MultiBuffers.
                totalCapacity += fillLists(arb.buffers);
            }
            else
            {
                this.originalBuffers.add(buffer);
                this.originalPositions.add(buffer.readPosition());
                buffer = buffer.slice();
                totalCapacity += buffer.capacity();
                this.buffers.add(buffer);
            }
        }
        return totalCapacity;
    }

    @Override
    public long readPosition()
    {
        return position;
    }

    @Override
    public RetainableByteBuffer readPosition(long newPosition)
    {
        if (newPosition > capacity)
            throw new IllegalArgumentException("newPosition(" + newPosition + ") > capacity(" + capacity + ")");
        this.position = newPosition;
        for (int i = 0; i < buffers.size(); i++)
        {
            RetainableByteBuffer currentRb = buffers.get(i);
            RetainableByteBuffer originalRb = originalBuffers.get(i);
            Long originalRbPosition = originalPositions.get(i);

            long currentLimit = currentRb.capacity();
            long nextLimit = Math.min(newPosition, currentLimit);
            currentRb.readPosition(nextLimit);
            originalRb.readPosition(originalRbPosition + nextLimit);
            newPosition -= currentLimit;
            newPosition = Math.max(0L, newPosition);
        }
        return this;
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
        for (RetainableByteBuffer buffer : buffers)
        {
            totalRemaining += buffer.remaining();
        }
        return totalRemaining;
    }

    @Override
    public boolean hasRemaining()
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            if (buffer.hasRemaining())
                return true;
        }
        return false;
    }

    // Readable

    private RetainableByteBuffer currentReadableBuffer()
    {
        long currentPosition = position;
        for (RetainableByteBuffer buffer : buffers)
        {
            currentPosition -= buffer.readPosition();
            if (currentPosition <= 0L && buffer.hasRemaining())
                return buffer;
        }
        throw new BufferUnderflowException();
    }

    private ByteBuffer fragmentedGet(RetainableByteBuffer buffer, int length)
    {
        if (remaining() < length)
            throw new BufferUnderflowException();
        ByteBuffer tmpBuf = ByteBuffer.allocate(length);
        position += buffer.remaining();
        while (buffer.hasRemaining())
        {
            tmpBuf.put(buffer.get());
        }
        while (tmpBuf.hasRemaining())
        {
            buffer = currentReadableBuffer();
            position += buffer.readPosition();
            position += buffer.remaining();
            while (tmpBuf.hasRemaining() && buffer.hasRemaining())
            {
                tmpBuf.put(buffer.get());
            }
        }
        return tmpBuf.flip();
    }

    private void consumeOriginalBuffers(long byteCount)
    {
        for (RetainableByteBuffer originalBuffer : originalBuffers)
        {
            if (!originalBuffer.hasRemaining())
                continue;
            if (originalBuffer.remaining() >= byteCount)
            {
                originalBuffer.readPosition(originalBuffer.readPosition() + byteCount);
                break;
            }
            else
            {
                long remaining = originalBuffer.remaining();
                originalBuffer.readPosition(originalBuffer.readPosition() + remaining);
                byteCount -= remaining;
            }
            if (byteCount == 0)
                break;
        }
    }

    @Override
    public byte get(long index)
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            long limit = buffer.capacity();
            if (limit > index)
                return buffer.get(index);
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public byte get()
    {
        RetainableByteBuffer buffer = currentReadableBuffer();
        position++;
        consumeOriginalBuffers(1);
        return buffer.get();
    }

    @Override
    public short getShort()
    {
        RetainableByteBuffer buffer = currentReadableBuffer();
        if (buffer.remaining() >= 2L)
        {
            consumeOriginalBuffers(2);
            position += 2L;
            return buffer.getShort();
        }
        short aShort = fragmentedGet(buffer, 2).getShort();
        consumeOriginalBuffers(2);
        return aShort;
    }

    @Override
    public short getShort(long index)
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            long limit = buffer.capacity();
            if (limit > index)
            {
                if (buffer.remaining() >= 2L)
                    return buffer.getShort();
                else
                    return fragmentedGet(buffer, 2).getShort();
            }
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public int getInt()
    {
        RetainableByteBuffer buffer = currentReadableBuffer();
        if (buffer.remaining() >= 4L)
        {
            consumeOriginalBuffers(4);
            position += 4L;
            return buffer.getInt();
        }
        int anInt = fragmentedGet(buffer, 4).getInt();
        consumeOriginalBuffers(4);
        return anInt;
    }

    @Override
    public int getInt(long index)
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            long limit = buffer.capacity();
            if (limit > index)
            {
                if (buffer.remaining() >= 4L)
                    return buffer.getInt();
                else
                    return fragmentedGet(buffer, 4).getInt();
            }
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public long getLong()
    {
        RetainableByteBuffer buffer = currentReadableBuffer();
        if (buffer.remaining() >= 8L)
        {
            consumeOriginalBuffers(8);
            position += 8L;
            return buffer.getLong();
        }
        long aLong = fragmentedGet(buffer, 8).getLong();
        consumeOriginalBuffers(8);
        return aLong;
    }

    @Override
    public long getLong(long index)
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            long limit = buffer.capacity();
            if (limit > index)
            {
                if (buffer.remaining() >= 8L)
                    return buffer.getLong();
                else
                    return fragmentedGet(buffer, 8).getLong();
            }
            index -= limit;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public void get(byte[] b, int off, int len)
    {
        RetainableByteBuffer buffer = currentReadableBuffer();
        if (buffer.remaining() >= len)
        {
            consumeOriginalBuffers(len);
            position += b.length;
            buffer.get(b, off, len);
            return;
        }
        fragmentedGet(buffer, len).get(b, off, len);
        consumeOriginalBuffers(len);
    }

    @Override
    public void get(long index, byte[] b, int off, int len)
    {
        // TODO
    }

    @Override
    public RetainableByteBuffer slice()
    {
        List<RetainableByteBuffer> copy = new ArrayList<>(buffers.size());
        for (RetainableByteBuffer buffer : buffers)
        {
            RetainableByteBuffer slice = buffer.slice();
            copy.add(slice);
        }
        RetainableByteBuffer result = new MultiBuffer(copy);
        copy.forEach(Retainable::release);
        return result;
    }

    @Override
    public RetainableByteBuffer slice(long position, long length)
    {
        if (position < 0)
            throw new IllegalArgumentException("position must be >= 0");
        if (length < 0)
            throw new IllegalArgumentException("length must be >= 0");
        if (position + length > capacity)
            throw new IllegalArgumentException("position(" + position + ") + length(" + length + ") must be <= capacity(" + capacity + ")");

        if (length == 0)
            return RetainableByteBuffer.empty();

        List<RetainableByteBuffer> copy = new ArrayList<>(buffers.size());

        int i;
        long seekPosition = position;
        // First, skip buffers up to position.
        for (i = 0; i < buffers.size(); i++)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            long limit = buffer.capacity();

            if (seekPosition < limit)
                break;
            seekPosition -= limit;
        }
        // Second, slice the remaining buffers up to length.
        for (; i < buffers.size(); i++)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            long subSlicePosition = seekPosition;
            long subSliceLength;
            seekPosition = 0L;

            long remaining = buffer.capacity() - subSlicePosition;
            if (length > remaining)
                subSliceLength = remaining;
            else
                subSliceLength = length;
            length -= remaining;

            RetainableByteBuffer slice = buffer.slice(subSlicePosition, subSliceLength);
            copy.add(slice);

            if (length <= 0L)
            {
                RetainableByteBuffer result = new MultiBuffer(copy);
                copy.forEach(Retainable::release);
                return result;
            }
        }
        throw new IllegalStateException("Should not happen");
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        boolean writeCalled = false;
        long totalWritten = 0L;
        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            if (!buffer.hasRemaining())
                continue;

            if (buffer instanceof SingleMutableBuffer single)
            {
                if (target instanceof GatheringTarget gatherer)
                {
                    long length = 0;
                    List<ByteBuffer> buffers = null;
                    for (int j = i + 1; j < this.buffers.size(); ++j)
                    {
                        RetainableByteBuffer b = this.buffers.get(j);
                        if (b instanceof SingleMutableBuffer s)
                        {
                            if (buffers == null)
                            {
                                buffers = new ArrayList<>();
                                buffers.add(single.getByteBuffer());
                                length += single.remaining();
                            }
                            buffers.add(s.getByteBuffer());
                            length += s.remaining();
                        }
                        else
                        {
                            break;
                        }
                    }
                    if (length > 0)
                    {
                        i += buffers.size() - 1;
                        writeCalled = true;
                        long written = gatherer.write(buffers.toArray(new ByteBuffer[0]));
                        if (written > 0)
                        {
                            position += written;
                            totalWritten += written;
                        }
                        if (written < length)
                            break;
                        else
                            continue;
                    }
                }
            }

            long length = buffer.remaining();
            writeCalled = true;
            long written = buffer.writeTo(target);
            if (written > 0)
            {
                position += written;
                totalWritten += written;
            }
            if (written < length)
                break;
        }
        // Call Target.write() with an empty NIO buffer when this buffer is empty.
        if (!writeCalled)
            target.write(BufferUtil.EMPTY_BUFFER);
        else
            consumeOriginalBuffers(totalWritten);
        return totalWritten;
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
            buffers.forEach(Retainable::release);
            buffers.clear();
            originalBuffers.clear();
            originalPositions.clear();
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
            buffers,
            retainable);
    }
}
