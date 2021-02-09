//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
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
    private CountingBufferPool byteBufferPool;
    private ByteBufferAccumulator accumulator;

    @BeforeEach
    public void before()
    {
        byteBufferPool = new CountingBufferPool();
        accumulator = new ByteBufferAccumulator(byteBufferPool, false);
    }

    @Test
    public void testToBuffer()
    {
        int size = 1024 * 1024;
        int allocationSize = 1024;
        ByteBuffer content = randomBytes(size);
        ByteBuffer slice = content.slice();

        // We completely fill up the internal buffer with the first write.
        ByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
        assertThat(BufferUtil.space(internalBuffer), greaterThanOrEqualTo(allocationSize));
        writeInFlushMode(slice, internalBuffer);
        assertThat(BufferUtil.space(internalBuffer), is(0));

        // If we ask for min size of 0 we get the same buffer which is full.
        internalBuffer = accumulator.ensureBuffer(0, allocationSize);
        assertThat(BufferUtil.space(internalBuffer), is(0));

        // If we need at least 1 minSpace we must allocate a new buffer.
        internalBuffer = accumulator.ensureBuffer(1, allocationSize);
        assertThat(BufferUtil.space(internalBuffer), greaterThan(0));
        assertThat(BufferUtil.space(internalBuffer), greaterThanOrEqualTo(allocationSize));

        // Write 13 bytes from the end of the internal buffer.
        int bytesToWrite = BufferUtil.space(internalBuffer) - 13;
        ByteBuffer buffer = BufferUtil.toBuffer(new byte[bytesToWrite]);
        BufferUtil.clear(buffer);
        assertThat(writeInFlushMode(slice, buffer), is(bytesToWrite));
        assertThat(writeInFlushMode(buffer, internalBuffer), is(bytesToWrite));
        assertThat(BufferUtil.space(internalBuffer), is(13));

        // If we request anything under the amount remaining we get back the same buffer.
        for (int i = 0; i <= 13; i++)
        {
            internalBuffer = accumulator.ensureBuffer(i, allocationSize);
            assertThat(BufferUtil.space(internalBuffer), is(13));
        }

        // If we request over 13 then we get a new buffer.
        internalBuffer = accumulator.ensureBuffer(14, allocationSize);
        assertThat(BufferUtil.space(internalBuffer), greaterThanOrEqualTo(1024));

        // Copy the rest of the content.
        while (slice.hasRemaining())
        {
            internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            assertThat(BufferUtil.space(internalBuffer), greaterThanOrEqualTo(1));
            writeInFlushMode(slice, internalBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(accumulator.getLength(), is(size));
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        ByteBuffer combinedBuffer = accumulator.toByteBuffer();
        assertThat(byteBufferPool.getLeasedBuffers(), is(1L));
        assertThat(accumulator.getLength(), is(size));
        assertThat(combinedBuffer, is(content));

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        byteBufferPool.verifyClosed();
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
            ByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            assertThat(BufferUtil.space(internalBuffer), greaterThanOrEqualTo(1));
            writeInFlushMode(slice, internalBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(accumulator.getLength(), is(size));
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        ByteBuffer combinedBuffer = accumulator.takeByteBuffer();
        assertThat(byteBufferPool.getLeasedBuffers(), is(1L));
        assertThat(accumulator.getLength(), is(0));
        accumulator.close();
        assertThat(byteBufferPool.getLeasedBuffers(), is(1L));
        assertThat(combinedBuffer, is(content));

        // Return the buffer and make sure all is returned to bufferPool.
        byteBufferPool.release(combinedBuffer);
        byteBufferPool.verifyClosed();
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
            ByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            writeInFlushMode(slice, internalBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(accumulator.getLength(), is(size));
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        byte[] combinedBuffer = accumulator.toByteArray();
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        assertThat(accumulator.getLength(), is(size));
        assertThat(BufferUtil.toBuffer(combinedBuffer), is(content));

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        byteBufferPool.verifyClosed();
    }

    @Test
    public void testEmptyToBuffer()
    {
        ByteBuffer combinedBuffer = accumulator.toByteBuffer();
        assertThat(combinedBuffer.remaining(), is(0));
        assertThat(byteBufferPool.getLeasedBuffers(), is(1L));
        accumulator.close();
        byteBufferPool.verifyClosed();
    }

    @Test
    public void testEmptyTakeBuffer()
    {
        ByteBuffer combinedBuffer = accumulator.takeByteBuffer();
        assertThat(combinedBuffer.remaining(), is(0));
        accumulator.close();
        assertThat(byteBufferPool.getLeasedBuffers(), is(1L));
        byteBufferPool.release(combinedBuffer);
        byteBufferPool.verifyClosed();
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
            ByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            writeInFlushMode(slice, internalBuffer);
        }

        // Check we have the same content as the original buffer.
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        ByteBuffer combinedBuffer = byteBufferPool.acquire(accumulator.getLength(), false);
        accumulator.writeTo(combinedBuffer);
        assertThat(accumulator.getLength(), is(size));
        assertThat(combinedBuffer, is(content));
        byteBufferPool.release(combinedBuffer);

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        byteBufferPool.verifyClosed();
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
            ByteBuffer internalBuffer = accumulator.ensureBuffer(1, allocationSize);
            writeInFlushMode(slice, internalBuffer);
        }

        // Writing to a buffer too small gives buffer overflow.
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        ByteBuffer combinedBuffer = BufferUtil.toBuffer(new byte[accumulator.getLength() - 1]);
        BufferUtil.clear(combinedBuffer);
        assertThrows(BufferOverflowException.class, () -> accumulator.writeTo(combinedBuffer));

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        byteBufferPool.verifyClosed();
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
        assertThat(byteBufferPool.getLeasedBuffers(), greaterThan(1L));
        ByteBuffer combinedBuffer = byteBufferPool.acquire(accumulator.getLength(), false);
        accumulator.writeTo(combinedBuffer);
        assertThat(accumulator.getLength(), is(size));
        assertThat(combinedBuffer, is(content));
        byteBufferPool.release(combinedBuffer);

        // Close the accumulator and make sure all is returned to bufferPool.
        accumulator.close();
        byteBufferPool.verifyClosed();
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

    public static class CountingBufferPool extends LeakTrackingByteBufferPool
    {
        private final AtomicLong _leasedBuffers = new AtomicLong(0);

        public CountingBufferPool()
        {
            this(new MappedByteBufferPool());
        }

        public CountingBufferPool(ByteBufferPool delegate)
        {
            super(delegate);
        }

        @Override
        public ByteBuffer acquire(int size, boolean direct)
        {
            _leasedBuffers.incrementAndGet();
            return super.acquire(size, direct);
        }

        @Override
        public void release(ByteBuffer buffer)
        {
            if (buffer != null)
                _leasedBuffers.decrementAndGet();
            super.release(buffer);
        }

        public long getLeasedBuffers()
        {
            return _leasedBuffers.get();
        }

        public void verifyClosed()
        {
            assertThat(_leasedBuffers.get(), is(0L));
            assertThat(getLeakedAcquires(), is(0L));
            assertThat(getLeakedReleases(), is(0L));
            assertThat(getLeakedResources(), is(0L));
            assertThat(getLeakedRemoves(), is(0L));
        }
    }
}
