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

package org.eclipse.jetty.io;

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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArrayByteBufferPoolTest
{
    @Test
    public void testMaxMemoryEviction()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, 20, Integer.MAX_VALUE, 40, 40);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, 20, 2);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, 20, 1);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool();

        pool.acquire(10, true);
        pool.acquire(10, true);

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(2L * ArrayByteBufferPool.DEFAULT_FACTOR));
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
        ArrayByteBufferPool pool = new ArrayByteBufferPool();
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
        ArrayByteBufferPool pool = new ArrayByteBufferPool();

        for (int i = 0; i < 3; i++)
        {
            RetainableByteBuffer buf1 = pool.acquire(10, true);
            assertThat(buf1, is(notNullValue()));
            assertThat(buf1.capacity(), is(ArrayByteBufferPool.DEFAULT_FACTOR));
            RetainableByteBuffer buf2 = pool.acquire(10, true);
            assertThat(buf2, is(notNullValue()));
            assertThat(buf2.capacity(), is(ArrayByteBufferPool.DEFAULT_FACTOR));
            buf1.release();
            buf2.release();

            RetainableByteBuffer buf3 = pool.acquire(16384 + 1, true);
            assertThat(buf3, is(notNullValue()));
            assertThat(buf3.capacity(), is(16384 + ArrayByteBufferPool.DEFAULT_FACTOR));
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
        assertThat(pool.getDirectMemory(), is(ArrayByteBufferPool.DEFAULT_FACTOR * 3L + 16384 + 32768L));
        assertThat(pool.getHeapMemory(), is(32768L));

        pool.clear();

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getHeapByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getHeapMemory(), is(0L));
    }

    @Test
    public void testQuadraticPool()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool.Quadratic();

        RetainableByteBuffer retain5 = pool.acquire(5, false);
        retain5.release();
        RetainableByteBuffer retain6 = pool.acquire(6, false);
        assertThat(retain6, sameInstance(retain5));
        retain6.release();
        RetainableByteBuffer retain9 = pool.acquire(9, false);
        assertThat(retain9, not(sameInstance(retain5)));
        retain9.release();

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
        b4.getByteBuffer().limit(b4.getByteBuffer().capacity() - 2);
        assertThat(pool.dump(), containsString("{capacity=4,inuse=3(75%)"));
    }

    @Test
    public void testEndiannessResetOnRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer buffer = bufferPool.acquire(10, true);
        assertThat(buffer.getByteBuffer().order(), Matchers.is(ByteOrder.BIG_ENDIAN));
        buffer.getByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.release(), is(true));
        assertThat(buffer.getByteBuffer().order(), Matchers.is(ByteOrder.BIG_ENDIAN));
    }
}
