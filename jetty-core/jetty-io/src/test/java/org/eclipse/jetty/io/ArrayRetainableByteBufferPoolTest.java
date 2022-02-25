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

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArrayRetainableByteBufferPoolTest
{
    @Test
    public void testMaxMemoryEviction()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(0, 10, 20, Integer.MAX_VALUE, 40, 40);

        List<RetainableByteBuffer> buffers = new ArrayList<>();

        buffers.add(pool.acquire(10, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(10, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(20, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(20, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(10, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(20, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(10, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        buffers.add(pool.acquire(20, true));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));

        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getDirectMemory(), greaterThan(0L));

        buffers.forEach(RetainableByteBuffer::release);

        assertThat(pool.getAvailableDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), lessThan((long)buffers.size()));
        assertThat(pool.getDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getDirectByteBufferCount(), lessThan((long)buffers.size()));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        assertThat(pool.getDirectMemory(), greaterThan(0L));
    }

    @Test
    public void testBelowMinCapacityDoesNotPool()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(1, true);
        assertThat(buf1.capacity(), is(1));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buf1.release();
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
    }

    @Test
    public void testOverMaxCapacityDoesNotPool()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(21, true);
        assertThat(buf1.capacity(), is(21));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buf1.release();
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
    }

    @Test
    public void testRetain()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(10, true);

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));

        assertThat(buf1.isRetained(), is(false));
        buf1.retain();
        buf1.retain();
        assertThat(buf1.isRetained(), is(true));
        assertThat(buf1.release(), is(false));
        assertThat(buf1.isRetained(), is(true));
        assertThat(buf1.release(), is(false));
        assertThat(buf1.isRetained(), is(false));

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));

        assertThat(buf1.release(), is(true));
        assertThat(buf1.isRetained(), is(false));

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));
    }

    @Test
    public void testTooManyReleases()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(10, true);

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));

        buf1.release();

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));

        assertThrows(IllegalStateException.class, buf1::release);

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));
    }

    @Test
    public void testMaxBucketSize()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(0, 10, 20, 2);

        RetainableByteBuffer buf1 = pool.acquire(1, true); // pooled
        assertThat(buf1.capacity(), is(10));
        RetainableByteBuffer buf2 = pool.acquire(1, true); // pooled
        assertThat(buf2.capacity(), is(10));
        RetainableByteBuffer buf3 = pool.acquire(1, true); // not pooled, bucket is full
        assertThat(buf3.capacity(), is(1));

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(20L));

        RetainableByteBuffer buf4 = pool.acquire(11, true); // pooled
        assertThat(buf4.capacity(), is(20));
        RetainableByteBuffer buf5 = pool.acquire(11, true); // pooled
        assertThat(buf5.capacity(), is(20));
        RetainableByteBuffer buf6 = pool.acquire(11, true); // not pooled, bucket is full
        assertThat(buf6.capacity(), is(11));

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getDirectMemory(), is(60L));
    }

    @Test
    public void testBufferReleaseRepools()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(0, 10, 20, 1);

        List<RetainableByteBuffer> all = new ArrayList<>();

        all.add(pool.acquire(1, true));  // pooled
        all.add(pool.acquire(1, true));  // not pooled, bucket is full
        all.add(pool.acquire(11, true));  // pooled
        all.add(pool.acquire(11, true));  // not pooled, bucket is full

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(30L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));

        all.forEach(RetainableByteBuffer::release);

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(30L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(2L));
        assertThat(pool.getAvailableDirectMemory(), is(30L));
    }

    @Test
    public void testFactorAndCapacity()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        pool.acquire(1, true);  // not pooled, < minCapacity
        pool.acquire(10, true); // pooled
        pool.acquire(20, true); // pooled
        pool.acquire(30, true); // not pooled, > maxCapacity

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(30L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
    }

    @Test
    public void testClearUnlinksLeakedBuffers()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool();

        pool.acquire(10, true);
        pool.acquire(10, true);

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(2048L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));

        pool.clear();

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
    }

    @Test
    public void testRetainAfterRePooledThrows()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool();
        RetainableByteBuffer buf1 = pool.acquire(10, true);
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(buf1.release(), is(true));
        assertThrows(IllegalStateException.class, buf1::retain);
        assertThrows(IllegalStateException.class, buf1::release);
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));

        // check that the buffer is still available
        RetainableByteBuffer buf2 = pool.acquire(10, true);
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(buf2 == buf1, is(true)); // make sure it's not a new instance
        assertThat(buf1.release(), is(true));
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));
    }

    @Test
    public void testAcquireRelease()
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool();

        for (int i = 0; i < 3; i++)
        {
            RetainableByteBuffer buf1 = pool.acquire(10, true);
            assertThat(buf1, is(notNullValue()));
            assertThat(buf1.capacity(), is(1024));
            RetainableByteBuffer buf2 = pool.acquire(10, true);
            assertThat(buf2, is(notNullValue()));
            assertThat(buf2.capacity(), is(1024));
            buf1.release();
            buf2.release();

            RetainableByteBuffer buf3 = pool.acquire(16384 + 1, true);
            assertThat(buf3, is(notNullValue()));
            assertThat(buf3.capacity(), is(16384 + 1024));
            buf3.release();

            RetainableByteBuffer buf4 = pool.acquire(32768, true);
            assertThat(buf4, is(notNullValue()));
            assertThat(buf4.capacity(), is(32768));
            buf4.release();

            RetainableByteBuffer buf5 = pool.acquire(32768, false);
            assertThat(buf5, is(notNullValue()));
            assertThat(buf5.capacity(), is(32768));
            buf5.release();
        }

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getHeapByteBufferCount(), is(1L));
        assertThat(pool.getDirectMemory(), is(1024 + 1024 + 16384 + 1024 + 32768L));
        assertThat(pool.getHeapMemory(), is(32768L));

        pool.clear();

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getHeapByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getHeapMemory(), is(0L));
    }

    @Test
    public void testExponentialPool() throws IOException
    {
        ArrayRetainableByteBufferPool pool = new ArrayRetainableByteBufferPool.ExponentialPool();
        assertThat(pool.acquire(1, false).capacity(), is(1));
        assertThat(pool.acquire(2, false).capacity(), is(2));
        RetainableByteBuffer b3 = pool.acquire(3, false);
        assertThat(b3.capacity(), is(4));
        RetainableByteBuffer b4 = pool.acquire(4, false);
        assertThat(b4.capacity(), is(4));

        int capacity = 4;
        while (true)
        {
            RetainableByteBuffer b = pool.acquire(capacity - 1, false);
            assertThat(b.capacity(), Matchers.is(capacity));
            b = pool.acquire(capacity, false);
            assertThat(b.capacity(), Matchers.is(capacity));

            if (capacity >= pool.getMaxCapacity())
                break;

            b = pool.acquire(capacity + 1, false);
            assertThat(b.capacity(), Matchers.is(capacity * 2));

            capacity = capacity * 2;
        }

        b3.release();
        b4.getBuffer().limit(b4.getBuffer().capacity() - 2);
        assertThat(pool.dump(), containsString("]{capacity=4,inuse=3(75%)"));
    }

    /**
     * A variant of the {@link ArrayRetainableByteBufferPool} that
     * uses buckets of buffers that increase in size by a power of
     * 2 (eg 1k, 2k, 4k, 8k, etc.).
     */
    public static class ExponentialPool extends ArrayRetainableByteBufferPool
    {
        public ExponentialPool()
        {
            this(0, -1, Integer.MAX_VALUE);
        }

        public ExponentialPool(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            this(minCapacity, maxCapacity, maxBucketSize, -1L, -1L);
        }

        public ExponentialPool(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity,
                -1,
                maxCapacity,
                maxBucketSize,
                maxHeapMemory,
                maxDirectMemory,
                c -> 32 - Integer.numberOfLeadingZeros(c - 1),
                i -> 1 << i);
        }
    }

    @Test
    public void testEndiannessResetOnRelease()
    {
        ArrayRetainableByteBufferPool bufferPool = new ArrayRetainableByteBufferPool();
        RetainableByteBuffer buffer = bufferPool.acquire(10, true);
        assertThat(buffer.getBuffer().order(), Matchers.is(ByteOrder.BIG_ENDIAN));
        buffer.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.release(), is(true));
        assertThat(buffer.getBuffer().order(), Matchers.is(ByteOrder.BIG_ENDIAN));
    }
}
