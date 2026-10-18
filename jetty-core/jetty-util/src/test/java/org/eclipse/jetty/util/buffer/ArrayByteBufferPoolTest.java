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

package org.eclipse.jetty.util.buffer;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.buffer.internal.CompoundPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDump(boolean statsEnabled)
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100}, 200, 200);
        pool.setStatisticsEnabled(statsEnabled);

        List<RetainableByteBuffer> buffers = new ArrayList<>();

        for (int i = 1; i < 151; i++)
        {
            buffers.add(pool.acquire(i, true));
        }

        buffers.forEach(RetainableByteBuffer::release);

        String dump = pool.dump();
        if (statsEnabled)
        {
            assertThat(dump, containsString("direct non-pooled acquisitions size=1\n"));
            assertThat(dump, containsString("200: 50 from "));
        }
        else
        {
            assertThat(dump, containsString("direct non-pooled acquisitions size=1\n"));
            assertThat(dump, containsString("0: 50\n"));
        }
        pool.clear();
        assertThat(pool.dump(), containsString("direct non-pooled acquisitions size=0\n"));
    }

    @Test
    public void testMaxMemoryEviction()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, 20}, 40, 40);

        List<RetainableByteBuffer> buffers = new ArrayList<>();

        for (int i = 0; i < 200; i++)
        {
            buffers.add(pool.acquire(10 + i / 10, true));
        }

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
        {
            buffers.add(pool.acquire(10 + i / 10, true));
        }

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
    public void testOverMaxCapacityDoesNotPool()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, 20});
        pool.setStatisticsEnabled(true);

        RetainableByteBuffer buf1 = pool.acquire(21, true);
        assertThat(buf1.capacity(), is(21L));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buf1.release();
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        Map<Integer, Long> noBucketDirectAcquires = pool.getNoBucketDirectAcquires();
        assertFalse(noBucketDirectAcquires.isEmpty());
        assertEquals(1L, noBucketDirectAcquires.get(40));

        RetainableByteBuffer buf3 = pool.acquire(41, true);
        buf3.release();
        noBucketDirectAcquires = pool.getNoBucketDirectAcquires();
        assertEquals(1L, noBucketDirectAcquires.get(60));
    }

    @Test
    public void testRetain()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, 20});

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
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, 20});

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
    public void testBufferReleaseRePools()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, 20});

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

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getDirectMemory(), is(60L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(4L));
        assertThat(pool.getAvailableDirectMemory(), is(60L));
    }

    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
        buckets
        12;15;18;21
        12;16;20
        10;15;20
        10;20
        """)
    public void testFactorCapacityAcquireRelease(String buckets)
    {
        String[] expectedBuckets = buckets.split(";");
        int[] bucketCapacities = Arrays.stream(expectedBuckets).mapToInt(Integer::parseInt).toArray();
        ArrayByteBufferPool pool = new ArrayByteBufferPool(bucketCapacities);
        pool.setStatisticsEnabled(true);

        int bucketCount = expectedBuckets.length;
        List<Map<String, Object>> stats = pool.getDirectBucketsStatistics();
        assertEquals(bucketCount, stats.size());
        for (int i = 0; i < bucketCount; ++i)
        {
            assertEquals(expectedBuckets[i], String.valueOf(stats.get(i).get("capacity")));
        }

        RetainableByteBuffer bufMin = pool.acquire(1, true);
        RetainableByteBuffer bufMid = pool.acquire(pool.getMaxCapacity() / 2, true);
        RetainableByteBuffer bufMax = pool.acquire(pool.getMaxCapacity(), true);
        RetainableByteBuffer bufOver = pool.acquire(pool.getMaxCapacity() + 1, true);

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));

        assertTrue(bufMin.release()); // pooled
        assertTrue(bufMid.release()); // pooled
        assertTrue(bufMax.release()); // pooled
        assertTrue(bufOver.release()); // not pooled, > maxCapacity

        long bufferCount = 3;
        assertThat(pool.getDirectByteBufferCount(), is(bufferCount));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(bufferCount));

        Map<Integer, Long> noBucketAcquires = pool.getNoBucketDirectAcquires();
        assertEquals(1, noBucketAcquires.size());
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
        assertThat(pool.getDirectMemory(), greaterThan(0L));

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
        assertThat(buf2, not(sameInstance(buf1)));

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
        int capacity = 4096;
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{capacity, 2 * capacity, 3 * capacity, 4 * capacity, 5 * capacity, 6 * capacity, 7 * capacity, 8 * capacity});

        for (int i = 0; i < 3; i++)
        {
            RetainableByteBuffer buf1 = pool.acquire(10, true);
            assertThat(buf1, is(notNullValue()));
            assertEquals(capacity, buf1.capacity());
            RetainableByteBuffer buf2 = pool.acquire(10, true);
            assertThat(buf2, is(notNullValue()));
            assertEquals(capacity, buf2.capacity());
            buf1.release();
            buf2.release();

            RetainableByteBuffer buf3 = pool.acquire(16384 + 1, true);
            assertThat(buf3, is(notNullValue()));
            assertEquals(16384 + capacity, buf3.capacity());
            buf3.release();

            RetainableByteBuffer buf4 = pool.acquire(32768, true);
            assertThat(buf4, is(notNullValue()));
            assertEquals(32768, buf4.capacity());
            buf4.release();

            RetainableByteBuffer buf5 = pool.acquire(32768, false);
            assertThat(buf5, is(notNullValue()));
            assertEquals(32768, buf5.capacity());
            buf5.release();
        }

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getHeapByteBufferCount(), is(1L));
        assertThat(pool.getDirectMemory(), is(capacity * 3L + 16384 + 32768L));
        assertThat(pool.getHeapMemory(), is(32768L));

        pool.clear();

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getHeapByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getHeapMemory(), is(0L));
    }

    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
        buckets
        1024;2048;4096;8192;16384;32768;65536
        128;256;512;1024
        2;4;8;16;32;64;128;256
        """)
    public void testQuadraticFactorCapacityAcquireRelease(String buckets)
    {
        String[] expectedBuckets = buckets.split(";");
        int[] bucketCapacities = Arrays.stream(expectedBuckets).mapToInt(Integer::parseInt).toArray();
        ArrayByteBufferPool pool = new ArrayByteBufferPool(bucketCapacities);
        pool.setStatisticsEnabled(true);

        int bucketCount = expectedBuckets.length;
        List<Map<String, Object>> stats = pool.getHeapBucketsStatistics();
        assertEquals(bucketCount, stats.size());
        for (int i = 0; i < bucketCount; ++i)
        {
            assertEquals(expectedBuckets[i], String.valueOf(stats.get(i).get("capacity")));
        }

        RetainableByteBuffer bufMin = pool.acquire(1, false);
        RetainableByteBuffer bufMid = pool.acquire(pool.getMaxCapacity() / 2, false);
        RetainableByteBuffer bufMax = pool.acquire(pool.getMaxCapacity(), false);
        RetainableByteBuffer bufOver = pool.acquire(pool.getMaxCapacity() + 1, false);

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getAvailableDirectMemory(), is(0L));

        assertTrue(bufMin.release()); // pooled
        assertTrue(bufMid.release()); // pooled
        assertTrue(bufMax.release()); // pooled
        assertTrue(bufOver.release()); // not pooled, > maxCapacity

        long bufferCount = 3L;
        assertThat(pool.getHeapByteBufferCount(), is(bufferCount));
        assertThat(pool.getAvailableHeapByteBufferCount(), is(bufferCount));

        Map<Integer, Long> noBucketAcquires = pool.getNoBucketHeapAcquires();
        assertEquals(1, noBucketAcquires.size());
    }

    @Test
    public void testWithBucketCapacitiesBucketSizes()
    {
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{1024, 65536});
            String dump = pool.dump();
            assertThat(dump, containsString("direct size=2\n"));
            assertThat(dump, containsString("[capacity=1024,"));
            assertThat(dump, containsString("[capacity=65536,"));
        }
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{30, 24});
            String dump = pool.dump();
            assertThat(dump, containsString("direct size=2\n"));
            assertThat(dump, containsString("[capacity=24,"));
            assertThat(dump, containsString("[capacity=30,"));
        }
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{3, 7, 100});
            String dump = pool.dump();
            assertThat(dump, containsString("direct size=3\n"));
            assertThat(dump, containsString("[capacity=3,"));
            assertThat(dump, containsString("[capacity=7,"));
            assertThat(dump, containsString("[capacity=100,"));
        }
    }

    @Test
    public void testUnsortedBucketCapacities()
    {
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{30, 24});
            RetainableByteBuffer buffer = pool.acquire(28, true);
            assertEquals(30, buffer.capacity());
            buffer.release();
        }
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{30, 10, 20});
            RetainableByteBuffer buffer = pool.acquire(15, true);
            assertEquals(20, buffer.capacity());
            buffer.release();
        }
    }

    @Test
    public void testInvalidBucketCapacities()
    {
        assertThrows(IllegalArgumentException.class, () -> new ArrayByteBufferPool(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new ArrayByteBufferPool(new int[]{0}));
        assertThrows(IllegalArgumentException.class, () -> new ArrayByteBufferPool(new int[]{1024, -1}));
    }

    @Test
    public void testWithBucketCapacitiesNoBucketSizes()
    {
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{1, 100});
            pool.setStatisticsEnabled(true);
            pool.acquire(200, false).release();
            pool.acquire(300, false).release();
            pool.acquire(800, false).release();
            pool.acquire(150, false).release();
            String dump = pool.dump();
            assertThat(dump, containsString("200: 2 from "));
            assertThat(dump, containsString("300: 1 from "));
            assertThat(dump, containsString("800: 1 from "));
        }
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{1, 7, 50, 100});
            pool.setStatisticsEnabled(true);
            pool.acquire(200, false).release();
            pool.acquire(300, false).release();
            pool.acquire(800, false).release();
            pool.acquire(150, false).release();
            String dump = pool.dump();
            assertThat(dump, containsString("200: 2 from "));
            assertThat(dump, containsString("300: 1 from "));
            assertThat(dump, containsString("800: 1 from "));
        }
        {
            ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{128, 512, 2048});
            pool.setStatisticsEnabled(true);
            pool.acquire(8192, false).release();
            String dump = pool.dump();
            assertThat(dump, containsString("8192: 1 from "));
        }
    }

    @Test
    public void testWithBucketCapacitiesStats()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{1024, 65536});
        pool.setStatisticsEnabled(true);
        for (int i = 0; i < 5; i++)
        {
            pool.acquire(1023, false).release();
        }
        pool.acquire(2048, false).release();
        pool.acquire(4096, false).release();
        pool.acquire(4096, false).release();
        pool.acquire(6144, false).release();
        pool.acquire(65536 + 1, false).release();
        pool.acquire(65536 + 2, false).release();
        pool.acquire(65536 * 2 + 1, false).release();
        pool.acquire(65536 * 3 - 1, false).release();
        String dump = pool.dump();
        assertThat(dump, containsString("[capacity=1024,in-use=0/1,pooled/acquires/releases=4/5/5(80.000%),avgSize=1023,non-pooled/evicts/removes=0/0/0]"));
        assertThat(dump, containsString("[capacity=65536,in-use=0/1,pooled/acquires/releases=3/4/4(75.000%),avgSize=4096,non-pooled/evicts/removes=0/0/0]"));
        assertThat(dump, containsString("131072: 2 from "));
        assertThat(dump, containsString("196608: 2 from "));
    }

    @Test
    public void testEndiannessResetOnRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool();
        RetainableByteBuffer buffer = bufferPool.acquire(10, true);
        buffer.quietRead(b ->
        {
            assertThat(b.order(), Matchers.is(ByteOrder.BIG_ENDIAN));
            b.order(ByteOrder.LITTLE_ENDIAN);
            return 0;
        });
        assertThat(buffer.release(), is(true));
        buffer.quietRead(b ->
        {
            assertThat(b.order(), Matchers.is(ByteOrder.BIG_ENDIAN));
            return 0;
        });
    }

    @Test
    public void testReleaseExcessMemory()
    {
        int maxCapacity = 20;
        int acquires = ConcurrentPool.OPTIMAL_MAX_SIZE * 2;
        int maxMemory = maxCapacity * acquires / 2;
        ArrayByteBufferPool pool = new ArrayByteBufferPool(new int[]{10, maxCapacity}, maxMemory, maxMemory);

        // It is always possible to acquire beyond maxMemory, because
        // the buffers are in use and not really retained in the pool.
        List<RetainableByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < acquires; ++i)
        {
            buffers.add(pool.acquire(maxCapacity, true));
        }

        // The last entries acquired are from the queued pool.
        // Release in reverse order to release first the queued
        // entries, but then the concurrent entries should be
        // pooled, and the queued entries removed.
        Collections.reverse(buffers);
        buffers.forEach(RetainableByteBuffer::release);

        Pool<RetainableByteBuffer.Mutable> bucketPool = pool.poolFor(maxCapacity, true);
        assertThat(bucketPool, instanceOf(CompoundPool.class));
        CompoundPool<RetainableByteBuffer.Mutable> compoundPool = (CompoundPool<RetainableByteBuffer.Mutable>)bucketPool;
        assertThat(compoundPool.getPrimaryPool().size(), is(ConcurrentPool.OPTIMAL_MAX_SIZE));
        assertThat(compoundPool.getSecondaryPool().size(), is(0));
    }

    @Test
    public void testCompoundPoolClear()
    {
        ArrayByteBufferPool pool = new ArrayByteBufferPool();

        // Populate the bucket past the primary ConcurrentPool, so the secondary QueuedPool holds entries.
        List<RetainableByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < ConcurrentPool.OPTIMAL_MAX_SIZE + 1; i++)
        {
            buffers.add(pool.acquire(1, false));
        }
        buffers.forEach(RetainableByteBuffer::release);

        pool.clear();

        pool.acquire(1, false).release();
    }
}
