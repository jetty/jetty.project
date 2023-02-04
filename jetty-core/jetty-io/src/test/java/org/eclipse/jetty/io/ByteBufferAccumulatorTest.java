//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByteBufferAccumulatorTest
{
    private CountingBufferPool bufferPool;
    private ByteBufferAccumulator accumulator;

    @BeforeEach
    public void before()
    {
        bufferPool = new CountingBufferPool();
        accumulator = new ByteBufferAccumulator(bufferPool, false);
    }

    @Test
    public void testToBuffer()
    {
        int size = 1024 * 1024;
        int allocationSize = 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // We completely fill up the internal buffer with the first write.
        RetainableByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
        ByteBuffer byteBuffer = internalBuffer.getByteBuffer();
        assertThat(BufferUtil.space(byteBuffer), greaterThanOrEqualTo(allocationSize));
        writeInFlushMode(slice, byteBuffer);
        assertThat(BufferUtil.space(byteBuffer), is(0));

        // If we ask for min size of 0 we get the same buffer which is full.
        internalBuffer = accumulator.ensureBuffer(0, allocationSize);
        byteBuffer = internalBuffer.getByteBuffer();
        assertThat(BufferUtil.space(byteBuffer), is(0));

        // If we need at least 1 minSpace we must allocate a new buffer.
        internalBuffer = accumulator.ensureBuffer(1, allocationSize);
        byteBuffer = internalBuffer.getByteBuffer();
        assertThat(BufferUtil.space(byteBuffer), greaterThan(0));
        assertThat(BufferUtil.space(byteBuffer), greaterThanOrEqualTo(allocationSize));

        // Write 13 bytes from the end of the internal buffer.
        int bytesToWrite = BufferUtil.space(byteBuffer) - 13;
        ByteBuffer buffer = BufferUtil.toBuffer(new byte[bytesToWrite]);
        BufferUtil.clear(buffer);
        assertThat(writeInFlushMode(slice, buffer), is(bytesToWrite));
        assertThat(writeInFlushMode(buffer, byteBuffer), is(bytesToWrite));
        assertThat(BufferUtil.space(byteBuffer), is(13));

        // If we request anything under the amount remaining we get back the same buffer.
        for (int i = 0; i <= 13; i++)
        {
            internalBuffer = accumulator.ensureBuffer(i, allocationSize);
            byteBuffer = internalBuffer.getByteBuffer();
            assertThat(BufferUtil.space(byteBuffer), is(13));
        }

        // If we request over 13 then we get a new buffer.
        internalBuffer = accumulator.ensureBuffer(14, allocationSize);
        byteBuffer = internalBuffer.getByteBuffer();
        assertThat(BufferUtil.space(byteBuffer), greaterThanOrEqualTo(1024));

        // Copy the rest of the content.
        while (slice.hasRemaining())
        {
            internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            byteBuffer = internalBuffer.getByteBuffer();
            assertThat(BufferUtil.space(byteBuffer), greaterThanOrEqualTo(1));
            writeInFlushMode(slice, byteBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(accumulator.getLength(), is(size));
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        RetainableByteBuffer combinedBuffer = accumulator.toRetainableByteBuffer();
        assertThat(bufferPool.getAcquireCount(), is(1L));
        assertThat(accumulator.getLength(), is(size));
        assertThat(combinedBuffer.getByteBuffer(), is(content));

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testTakeBuffer()
    {
        int size = 1024 * 1024;
        int allocationSize = 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // Copy the content.
        while (slice.hasRemaining())
        {
            RetainableByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            ByteBuffer byteBuffer = internalBuffer.getByteBuffer();
            assertThat(BufferUtil.space(byteBuffer), greaterThanOrEqualTo(1));
            writeInFlushMode(slice, byteBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(accumulator.getLength(), is(size));
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        RetainableByteBuffer combinedBuffer = accumulator.takeRetainableByteBuffer();
        assertThat(bufferPool.getAcquireCount(), is(1L));
        assertThat(accumulator.getLength(), is(0));
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(1L));
        assertThat(combinedBuffer.getByteBuffer(), is(content));

        // Return the buffer and make sure all is returned to bufferPool.
        combinedBuffer.release();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testToByteArray()
    {
        int size = 1024 * 1024;
        int allocationSize = 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // Copy the content.
        while (slice.hasRemaining())
        {
            RetainableByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            ByteBuffer byteBuffer = internalBuffer.getByteBuffer();
            writeInFlushMode(slice, byteBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(accumulator.getLength(), is(size));
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        byte[] combinedBuffer = accumulator.toByteArray();
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        assertThat(accumulator.getLength(), is(size));
        assertThat(BufferUtil.toBuffer(combinedBuffer), is(content));

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testEmptyToBuffer()
    {
        RetainableByteBuffer combinedBuffer = accumulator.toRetainableByteBuffer();
        assertThat(combinedBuffer.remaining(), is(0));
        assertThat(bufferPool.getAcquireCount(), is(1L));
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testEmptyTakeBuffer()
    {
        RetainableByteBuffer combinedBuffer = accumulator.takeRetainableByteBuffer();
        assertThat(combinedBuffer.remaining(), is(0));
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(1L));
        combinedBuffer.release();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testWriteTo()
    {
        int size = 1024 * 1024;
        int allocationSize = 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // Copy the content.
        while (slice.hasRemaining())
        {
            RetainableByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            ByteBuffer byteBuffer = internalBuffer.getByteBuffer();
            writeInFlushMode(slice, byteBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        RetainableByteBuffer combinedBuffer = bufferPool.acquire(accumulator.getLength(), false);
        accumulator.writeTo(combinedBuffer.getByteBuffer());
        assertThat(accumulator.getLength(), is(size));
        assertThat(combinedBuffer.getByteBuffer(), is(content));
        combinedBuffer.release();

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testWriteToBufferTooSmall()
    {
        int size = 1024 * 1024;
        int allocationSize = 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // Copy the content.
        while (slice.hasRemaining())
        {
            RetainableByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            ByteBuffer byteBuffer = internalBuffer.getByteBuffer();
            writeInFlushMode(slice, byteBuffer);
        }

        // Writing to a buffer too small gives buffer overflow.
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        ByteBuffer combinedBuffer = BufferUtil.toBuffer(new byte[accumulator.getLength() - 1]);
        BufferUtil.clear(combinedBuffer);
        assertThrows(BufferOverflowException.class, () -> accumulator.writeTo(combinedBuffer));

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    @Test
    public void testCopy()
    {
        int size = 1024 * 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // Copy the content.
        int tmpBufferSize = 1024;
        ByteBuffer tmpBuffer = BufferUtil.toBuffer(new byte[tmpBufferSize]);
        BufferUtil.clear(tmpBuffer);
        while (slice.hasRemaining())
        {
            writeInFlushMode(slice, tmpBuffer);
            accumulator.copyBuffer(tmpBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(bufferPool.getAcquireCount(), greaterThan(1L));
        RetainableByteBuffer combinedBuffer = bufferPool.acquire(accumulator.getLength(), false);
        accumulator.writeTo(combinedBuffer.getByteBuffer());
        assertThat(accumulator.getLength(), is(size));
        assertThat(combinedBuffer.getByteBuffer(), is(content));
        combinedBuffer.release();

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        assertThat(bufferPool.getAcquireCount(), is(0L));
    }

    private ByteBuffer randomBytes(int size)
    {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        return BufferUtil.toBuffer(data);
    }

    private int writeInFlushMode(ByteBuffer from, ByteBuffer to)
    {
        int pos = BufferUtil.flipToFill(to);
        int written = BufferUtil.put(from, to);
        BufferUtil.flipToFlush(to, pos);
        return written;
    }

    private static class CountingBufferPool extends ByteBufferPool.Wrapper
    {
        private final AtomicLong _acquires = new AtomicLong();

        public CountingBufferPool()
        {
            super(new ArrayRetainableByteBufferPool());
        }

        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            _acquires.incrementAndGet();
            return new RetainableByteBuffer.Wrapper(super.acquire(size, direct))
            {
                @Override
                public boolean release()
                {
                    boolean released = super.release();
                    if (released)
                        _acquires.decrementAndGet();
                    return released;
                }
            };
        }

        public long getAcquireCount()
        {
            return _acquires.get();
        }
    }
}
