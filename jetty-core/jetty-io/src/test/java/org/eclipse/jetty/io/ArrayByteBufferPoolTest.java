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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.internal.CompoundPool;
import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.Pool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArrayByteBufferPoolTest
{
    @Test
    public void testRemoveAndReleaseCompoundPool()
    {
        int bufferCount = ConcurrentPool.OPTIMAL_MAX_SIZE * 2;
        List<RetainableByteBuffer> rbbs = new ArrayList<>();

        ArrayByteBufferPool pool = new ArrayByteBufferPool();
        for (int i = 0; i < bufferCount; i++)
        {
            RetainableByteBuffer rbb = pool.acquire(1, false);
            rbbs.add(rbb);
        }
        rbbs.forEach(Retainable::release);
        rbbs.clear();

        for (int i = 0; i < bufferCount; i++)
        {
            RetainableByteBuffer rbb = pool.acquire(1, false);
            rbbs.add(rbb);
        }

        rbbs.forEach(pool::removeAndRelease);
    }

    @Test
    public void testDump()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, 100, Integer.MAX_VALUE, 200, 200);
        pool.setStatisticsEnabled(true);

        List<RetainableByteBuffer> buffers = new ArrayList<>();

        for (int i = 1; i < 151; i++)
            buffers.add(pool.acquire(i, true));

        buffers.forEach(RetainableByteBuffer::release);

        String dump = pool.dump();
        assertThat(dump, containsString("direct non-pooled acquisitions size=5\n"));
        assertThat(dump, containsString("110: 10\n"));
        assertThat(dump, containsString("120: 10\n"));
        assertThat(dump, containsString("130: 10\n"));
        assertThat(dump, containsString("140: 10\n"));
        assertThat(dump, containsString("150: 10\n"));
        pool.clear();
        assertThat(pool.dump(), containsString("direct non-pooled acquisitions size=0\n"));
    }

    @Test
    public void testMaxMemoryEviction()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, 20, Integer.MAX_VALUE, 40, 40);

        List<RetainableByteBuffer> buffers = new ArrayList<>();

        for (int i = 0; i < 200; i++)
            buffers.add(pool.acquire(10 + i / 10, true));

        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buffers.forEach(RetainableByteBuffer::release);

        assertThat(pool.getAvailableDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), lessThan((long)buffers.size()));
        assertThat(pool.getDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getDirectByteBufferCount(), lessThan((long)buffers.size()));
        assertThat(pool.getDirectMemory(), greaterThan(0L));
        assertThat(pool.getDirectMemory(), lessThan(120L));

        buffers.clear();
        for (int i = 0; i < 200; i++)
            buffers.add(pool.acquire(10 + i / 10, true));

        long maxSize = 0;
        for (RetainableByteBuffer buffer : buffers)
        {
            buffer.release();
            long size = pool.getDirectMemory();
            maxSize = Math.max(size, maxSize);
        }

        // Test that size is never too much over target max
        assertThat(maxSize, lessThan(100L));
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
        pool.setStatisticsEnabled(true);

        RetainableByteBuffer buf1 = pool.acquire(21, true);
        assertThat(buf1.capacity(), is(21));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buf1.release();
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        Map<Integer, Long> noBucketDirectAcquires = pool.getNoBucketDirectAcquires();
        assertFalse(noBucketDirectAcquires.isEmpty());
        assertEquals(1L, noBucketDirectAcquires.get(30));
    }

    @Test
    public void testRetain()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(10, true);

        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(0L));

        assertThat(buf1.isRetained(), is(false));
        buf1.retain();
        buf1.retain();
        assertThat(buf1.isRetained(), is(true));
        assertThat(buf1.release(), is(false));
        assertThat(buf1.isRetained(), is(true));
        assertThat(buf1.release(), is(false));
        assertThat(buf1.isRetained(), is(false));

        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(0L));

        assertThat(buf1.release(), is(true));
        assertThat(buf1.isRetained(), is(false));

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));

        buf1 = pool.acquire(10, true);

        assertThat(pool.getDirectMemory(), is(10L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));

        buf1.release();
    }

    @Test
    public void testTooManyReleases()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(10, true);

        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), is(0L));

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

        RetainableByteBuffer buf1 = pool.acquire(1, true);
        assertThat(buf1.capacity(), is(10));
        RetainableByteBuffer buf2 = pool.acquire(1, true);
        assertThat(buf2.capacity(), is(10));
        RetainableByteBuffer buf3 = pool.acquire(1, true);
        assertThat(buf3.capacity(), is(10));

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        assertTrue(buf1.release()); // pooled
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertTrue(buf2.release()); // pooled
        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertTrue(buf3.release()); // not pooled, bucket is full.
        assertThat(pool.getDirectByteBufferCount(), is(2L));

        RetainableByteBuffer buf4 = pool.acquire(11, true);
        assertThat(buf4.capacity(), is(20));
        RetainableByteBuffer buf5 = pool.acquire(11, true);
        assertThat(buf5.capacity(), is(20));
        RetainableByteBuffer buf6 = pool.acquire(11, true);
        assertThat(buf6.capacity(), is(20));

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(20L));

        assertTrue(buf4.release()); // pooled
        assertThat(pool.getDirectByteBufferCount(), is(3L));
        assertTrue(buf5.release()); // pooled
        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertTrue(buf6.release()); // not pooled, bucket is full.
        assertThat(pool.getDirectByteBufferCount(), is(4L));
    }

    @Test
    public void testBufferReleaseRePools()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, 20, 1);

        List<RetainableByteBuffer> all = new ArrayList<>();

        all.add(pool.acquire(1, true));
        all.add(pool.acquire(1, true));
        all.add(pool.acquire(11, true));
        all.add(pool.acquire(11, true));

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
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

        RetainableByteBuffer buf1 = pool.acquire(1, true);
        RetainableByteBuffer buf2 = pool.acquire(10, true);
        RetainableByteBuffer buf3 = pool.acquire(20, true);
        RetainableByteBuffer buf4 = pool.acquire(30, true);

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));

        assertTrue(buf1.release()); // not pooled, < minCapacity
        assertTrue(buf2.release()); // pooled
        assertTrue(buf3.release()); // pooled
        assertTrue(buf4.release()); // not pooled, > maxCapacity

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(30L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(2L));
        assertThat(pool.getAvailableDirectMemory(), is(30L));
    }

    @Test
    public void testClearUnlinksLeakedBuffers()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool();

        RetainableByteBuffer buffer1 = pool.acquire(10, true);
        RetainableByteBuffer buffer2 = pool.acquire(10, true);

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));

        buffer2.release();
        buffer1.release();

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(2L * ArrayByteBufferPool.DEFAULT_FACTOR));

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

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));

        assertThat(buf1.release(), is(true));
        assertThrows(IllegalStateException.class, buf1::retain);
        assertThrows(IllegalStateException.class, buf1::release);
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));

        // Check that the buffer is still available.
        RetainableByteBuffer buf2 = pool.acquire(10, true);
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        // The ByteBuffer is re-wrapped by a different RetainableByteBuffer upon the first release.
        assertThat(buf2, not(sameInstance(buf1)));
        assertThat(buf2.getByteBuffer(), sameInstance(buf1.getByteBuffer()));

        assertThat(buf2.release(), is(true));
        assertThat(pool.getDirectByteBufferCount(), is(1L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(1L));

        RetainableByteBuffer buf3 = pool.acquire(10, true);
        assertThat(buf3, sameInstance(buf2));
        assertThat(buf3.release(), is(true));
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
        assertThat(retain6, not(sameInstance(retain5)));
        assertThat(retain6.getByteBuffer(), sameInstance(retain5.getByteBuffer()));
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

    @Test
    public void testReleaseExcessMemory()
    {
        int maxCapacity = 20;
        int maxBucketSize = ConcurrentPool.OPTIMAL_MAX_SIZE * 2;
        int maxMemory = maxCapacity * maxBucketSize / 2;
        ArrayByteBufferPool pool = new ArrayByteBufferPool(0, 10, maxCapacity, maxBucketSize, maxMemory, maxMemory);

        // It is always possible to acquire beyond maxMemory, because
        // the buffers are in use and not really retained in the pool.
        List<RetainableByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < maxBucketSize; ++i)
        {
            buffers.add(pool.acquire(maxCapacity, true));
        }

        // The last entries acquired are from the queued pool.
        // Release in reverse order to release first the queued
        // entries, but then the concurrent entries should be
        // pooled, and the queued entries removed.
        Collections.reverse(buffers);
        buffers.forEach(RetainableByteBuffer::release);

        Pool<RetainableByteBuffer> bucketPool = pool.poolFor(maxCapacity, true);
        assertThat(bucketPool, instanceOf(CompoundPool.class));
        CompoundPool<RetainableByteBuffer> compoundPool = (CompoundPool<RetainableByteBuffer>)bucketPool;
        assertThat(compoundPool.getPrimaryPool().size(), is(ConcurrentPool.OPTIMAL_MAX_SIZE));
        assertThat(compoundPool.getSecondaryPool().size(), is(0));
    }

    @Test
    public void testRemoveAndRelease()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool();

        RetainableByteBuffer reserved0 = pool.acquire(1024, false);
        RetainableByteBuffer reserved1 = pool.acquire(1024, false);

        RetainableByteBuffer acquired0 = pool.acquire(1024, false);
        acquired0.release();
        acquired0 = pool.acquire(1024, false);
        RetainableByteBuffer acquired1 = pool.acquire(1024, false);
        acquired1.release();
        acquired1 = pool.acquire(1024, false);

        RetainableByteBuffer retained0 = pool.acquire(1024, false);
        retained0.release();
        retained0 = pool.acquire(1024, false);
        retained0.retain();
        RetainableByteBuffer retained1 = pool.acquire(1024, false);
        retained1.release();
        retained1 = pool.acquire(1024, false);
        retained1.retain();

        assertTrue(pool.removeAndRelease(reserved1));
        assertTrue(pool.removeAndRelease(acquired1));
        assertFalse(pool.removeAndRelease(retained1));
        assertTrue(retained1.release());

        assertThat(pool.getHeapByteBufferCount(), is(2L));
        assertTrue(reserved0.release());
        assertThat(pool.getHeapByteBufferCount(), is(3L));
        assertTrue(acquired0.release());
        assertThat(pool.getHeapByteBufferCount(), is(3L));
        assertFalse(retained0.release());
        assertTrue(retained0.release());
        assertThat(pool.getHeapByteBufferCount(), is(3L));
    }
}
