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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class MultiBuffer implements RetainableByteBuffer
{
    public static RetainableByteBuffer merge(List<RetainableByteBuffer> list)
    {
        List<RetainableByteBuffer> buffers = new ArrayList<>();
        flatten(list, buffers);
        if (buffers.isEmpty())
            return RetainableByteBuffer.empty();
        if (buffers.size() == 1)
            return buffers.getFirst();
        return new MultiBuffer(buffers);
    }

    private static void flatten(List<RetainableByteBuffer> input, List<RetainableByteBuffer> output)
    {
        for (RetainableByteBuffer buffer : input)
        {
            if (buffer == null || !buffer.hasRemaining())
                continue;
            if (buffer instanceof MultiBuffer mb)
                flatten(mb.buffers, output);
            else
                output.add(buffer.sliceAndConsume(buffer.remaining()));
        }
    }

    private final ReferenceCounter refCount = new ReferenceCounter();
    private final List<RetainableByteBuffer> buffers;
    private final long capacity;

    private MultiBuffer(List<RetainableByteBuffer> buffers)
    {
        this.buffers = buffers;
        long capacity = 0;
        for (int i = 0; i < buffers.size(); i++)
        {
            capacity += buffers.get(i).capacity();
        }
        this.capacity = capacity;
    }

    @Override
    public long readPosition()
    {
        long r = 0;
        for (RetainableByteBuffer buffer : buffers)
        {
            if (buffer.hasRemaining())
            {
                r += buffer.readPosition();
                break;
            }
            r += buffer.capacity();
        }
        return r;
    }

    @Override
    public RetainableByteBuffer readPosition(long newPosition)
    {
        if (newPosition < 0 || newPosition > capacity())
            throw new IllegalArgumentException();

        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            if (newPosition <= buffer.capacity())
            {
                buffer.readPosition(newPosition);
                for (int j = i + 1; j < buffers.size(); ++j)
                {
                    buffers.get(j).readPosition(0);
                }
                break;
            }
            buffer.consume(buffer.remaining());
            newPosition -= buffer.capacity();
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
        long r = 0;
        for (RetainableByteBuffer buffer : buffers)
        {
            r += buffer.remaining();
        }
        return r;
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

    @Override
    public boolean isDirect()
    {
        boolean r = true;
        for (RetainableByteBuffer buffer : buffers)
        {
            r &= buffer.isDirect();
        }
        return r;
    }

    private RetainableByteBuffer current()
    {
        for (RetainableByteBuffer buffer : buffers)
        {
            if (buffer.hasRemaining())
                return buffer;
        }
        throw new BufferUnderflowException();
    }

    private ByteBuffer fragmented(int bytes)
    {
        if (bytes > remaining())
            throw new BufferUnderflowException();
        ByteBuffer byteBuffer = ByteBuffer.allocate(bytes);
        for (int i = 0; i < bytes; ++i)
        {
            byteBuffer.put(get());
        }
        return byteBuffer.flip();
    }

    private ByteBuffer fragmented(int index, long position, int bytes)
    {
        // Example: fragmented(1, 3, 8)
        // b0     b1      b2  b3
        // |----| |-----| |-| |------|
        // Since index=1, b0 is skipped.
        // b1.capacity=5, position=3, so we need to copy 2 of bytes=8.
        // Set position=0 and bytes=6
        // b2.capacity=1, so copy 1 byte
        // Set position=0 and bytes=5
        // b3.capacity=6, so bytes=5 are in the same buffer, copy 5 bytes and return.

        ByteBuffer byteBuffer = ByteBuffer.allocate(bytes);
        for (int i = index; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);

            // All the necessary bytes are in the same buffer.
            if (position + bytes <= buffer.capacity())
            {
                for (int j = 0; j < bytes; ++j)
                {
                    byteBuffer.put(buffer.get(position + j));
                }
                return byteBuffer.flip();
            }

            // Here the bytes span 2 or more buffers.

            // Copy the remaining bytes of the current buffer starting at position.
            int remaining = (int)(buffer.capacity() - position);
            for (int j = 0; j < remaining; ++j)
            {
                byteBuffer.put(buffer.get(position + j));
            }

            // The next buffer will be read at position 0.
            position = 0;
            // Decrement the number of bytes to copy and loop.
            bytes -= remaining;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public byte get()
    {
        return current().get();
    }

    @Override
    public byte get(long position)
    {
        if (position < 0 || position + Byte.BYTES > capacity())
            throw new IllegalArgumentException();

        for (RetainableByteBuffer buffer : buffers)
        {
            long capacity = buffer.capacity();
            if (position < capacity)
                return buffer.get(position);
            position -= capacity;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public short getShort()
    {
        int bytes = Short.BYTES;
        RetainableByteBuffer buffer = current();
        if (buffer.remaining() >= bytes)
            return buffer.getShort();
        return fragmented(bytes).getShort();
    }

    @Override
    public short getShort(long position)
    {
        int bytes = Short.BYTES;

        if (position < 0 || position + bytes > capacity())
            throw new IllegalArgumentException();

        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            long capacity = buffer.capacity();
            if (position < capacity)
            {
                if (position + bytes <= capacity)
                    return buffer.getShort(position);
                return fragmented(i, position, bytes).getShort();
            }
            position -= capacity;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public int getInt()
    {
        int bytes = Integer.BYTES;
        RetainableByteBuffer buffer = current();
        if (buffer.remaining() >= bytes)
            return buffer.getInt();
        return fragmented(bytes).getInt();
    }

    @Override
    public int getInt(long position)
    {
        int bytes = Integer.BYTES;

        if (position < 0 || position + bytes > capacity())
            throw new IllegalArgumentException();

        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            long capacity = buffer.capacity();
            if (position < capacity)
            {
                if (position + bytes <= capacity)
                    return buffer.getInt(position);
                return fragmented(i, position, bytes).getInt();
            }
            position -= capacity;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public long getLong()
    {
        int bytes = Long.BYTES;
        RetainableByteBuffer buffer = current();
        if (buffer.remaining() >= bytes)
            return buffer.getLong();
        return fragmented(bytes).getLong();
    }

    @Override
    public long getLong(long position)
    {
        int bytes = Long.BYTES;

        if (position < 0 || position + bytes > capacity())
            throw new IllegalArgumentException();

        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            long capacity = buffer.capacity();
            if (position < capacity)
            {
                if (position + bytes <= capacity)
                    return buffer.getLong(position);
                return fragmented(i, position, bytes).getLong();
            }
            position -= capacity;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public void get(byte[] bytes, int offset, int length)
    {
        if (length > remaining())
            throw new BufferUnderflowException();

        while (length > 0)
        {
            RetainableByteBuffer buffer = current();
            int l = (int)Math.min(length, buffer.remaining());
            buffer.get(bytes, offset, l);
            offset += l;
            length -= l;
        }
    }

    @Override
    public void get(long position, byte[] bytes, int offset, int length)
    {
        if (position + length > capacity())
            throw new BufferUnderflowException();

        if (length == 0)
            return;

        for (RetainableByteBuffer buffer : buffers)
        {
            long capacity = buffer.capacity();
            if (position < capacity)
            {
                int l = (int)Math.min(length, capacity - position);
                buffer.get(position, bytes, offset, l);
                offset += l;
                length -= l;
                if (length == 0)
                    return;
                // The next buffer is read from its beginning.
                position = 0;
            }
            else
            {
                position -= capacity;
            }
        }
        throw new BufferUnderflowException();
    }

    @Override
    public RetainableByteBuffer slice(long position, long length)
    {
        if (position < 0 || length < 0 || position + length > capacity())
            throw new IllegalArgumentException();

        if (length == 0)
            return RetainableByteBuffer.empty();

        List<RetainableByteBuffer> slices = new ArrayList<>();
        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer element = buffers.get(i);
            long capacity = element.capacity();
            if (position < capacity)
            {
                for (int j = i; j < buffers.size(); ++j)
                {
                    RetainableByteBuffer buffer = buffers.get(j);
                    long remaining = buffer.capacity() - position;
                    if (length <= remaining)
                    {
                        slices.add(buffer.slice(position, length));
                        return new MultiBuffer(slices);
                    }

                    slices.add(buffer.slice(position, remaining));

                    // The next buffer will be sliced at position 0.
                    position = 0;
                    // Decrement the number of bytes to slice and loop.
                    length -= remaining;
                }
                throw new BufferUnderflowException();
            }
            position -= capacity;
        }
        throw new BufferUnderflowException();
    }

    @Override
    public long writeTo(Target target) throws IOException
    {
        long totalWritten = 0;
        boolean invoked = false;
        GatheringTarget gatherer = target instanceof GatheringTarget gt ? gt : null;
        for (int i = 0; i < buffers.size(); ++i)
        {
            RetainableByteBuffer buffer = buffers.get(i);
            if (!buffer.hasRemaining())
                continue;

            if (gatherer != null)
            {
                // We can gather and the current buffer is a single,
                // check if the next buffers are also singles.
                if (buffer instanceof SingleMutableBuffer single)
                {
                    int idx = 0;
                    ByteBuffer[] gathers = null;
                    long length = 0;
                    for (int j = i + 1; j < buffers.size(); ++j)
                    {
                        RetainableByteBuffer next = buffers.get(j);
                        if (!next.hasRemaining())
                            continue;
                        if (next instanceof SingleMutableBuffer nextSingle)
                        {
                            if (gathers == null)
                            {
                                gathers = new ByteBuffer[buffers.size()];
                                gathers[idx++] = single.getByteBuffer();
                                length = single.remaining();
                            }
                            gathers[idx++] = nextSingle.getByteBuffer();
                            length += nextSingle.remaining();
                        }
                        else
                        {
                            break;
                        }
                    }
                    if (idx > 0)
                    {
                        invoked = true;
                        long written = gatherer.write(gathers, 0, idx);
                        if (written > 0)
                            totalWritten += written;
                        if (written < length)
                            break;
                        else
                            continue;
                    }
                }
            }

            invoked = true;
            long length = buffer.remaining();
            long written = buffer.writeTo(target);
            if (written > 0)
                totalWritten += written;
            if (written < length)
                break;
        }

        if (!invoked)
            target.write(BufferUtil.EMPTY_BUFFER);

        return totalWritten;
    }

    @Override
    public boolean isRetained()
    {
        return refCount.isRetained();
    }

    @Override
    public void retain()
    {
        refCount.retain();
    }

    @Override
    public boolean release()
    {
        boolean released = refCount.release();
        if (released)
            buffers.forEach(RetainableByteBuffer::release);
        return released;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[rc=%s,bs=%s]",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            refCount.getCount(),
            buffers);
    }
}
