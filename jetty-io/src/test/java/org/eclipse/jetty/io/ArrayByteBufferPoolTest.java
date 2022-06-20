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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

import org.eclipse.jetty.io.AbstractByteBufferPool.Bucket;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArrayByteBufferPoolTest
{
    @Test
    public void testMinimumRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10, 100, 1000);
        Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = 1; size <= 9; size++)
        {
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertEquals(size, buffer.capacity());
            for (Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }

            bufferPool.release(buffer);

            for (Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }
        }
    }

    @Test
    public void testMaxRelease()
    {
        int minCapacity = 10;
        int factor = 1;
        int maxCapacity = 1024;
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(minCapacity, factor, maxCapacity);
        Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = maxCapacity - 1; size <= maxCapacity + 1; size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
            for (Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }

            bufferPool.release(buffer);

            int pooled = Arrays.stream(buckets)
                .filter(Objects::nonNull)
                .mapToInt(AbstractByteBufferPool.Bucket::size)
                .sum();

            if (size <= maxCapacity)
                assertThat(pooled, is(1));
            else
                assertThat(pooled, is(0));
        }
    }

    @Test
    public void testAcquireRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10, 100, 1000);
        Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = 390; size <= 510; size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
            for (Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }

            bufferPool.release(buffer);

            int pooled = Arrays.stream(buckets)
                .filter(Objects::nonNull)
                .mapToInt(AbstractByteBufferPool.Bucket::size)
                .sum();
            assertEquals(1, pooled);
        }
    }

    @Test
    public void testAcquireReleaseAcquire()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10, 100, 1000);
        Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = 390; size <= 510; size++)
        {
            bufferPool.clear();
            ByteBuffer buffer1 = bufferPool.acquire(size, true);
            bufferPool.release(buffer1);
            ByteBuffer buffer2 = bufferPool.acquire(size, true);
            bufferPool.release(buffer2);
            ByteBuffer buffer3 = bufferPool.acquire(size, false);
            bufferPool.release(buffer3);

            int pooled = Arrays.stream(buckets)
                .filter(Objects::nonNull)
                .mapToInt(AbstractByteBufferPool.Bucket::size)
                .sum();
            assertEquals(1, pooled);

            assertSame(buffer1, buffer2);
            assertNotSame(buffer1, buffer3);
        }
    }

    @Test
    public void testReleaseNonPooledBuffer()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool();

        // Release a few small non-pool buffers
        bufferPool.release(ByteBuffer.wrap(StringUtil.getUtf8Bytes("Hello")));

        assertEquals(0, bufferPool.getHeapByteBufferCount());
    }

    @Test
    public void testMaxQueue()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(-1, -1, -1, 2);

        ByteBuffer buffer1 = bufferPool.acquire(512, false);
        ByteBuffer buffer2 = bufferPool.acquire(512, false);
        ByteBuffer buffer3 = bufferPool.acquire(512, false);

        Bucket[] buckets = bufferPool.bucketsFor(false);
        Arrays.stream(buckets)
            .filter(Objects::nonNull)
            .forEach(b -> assertEquals(0, b.size()));

        bufferPool.release(buffer1);
        Bucket bucket = Arrays.stream(buckets)
            .filter(Objects::nonNull)
            .filter(b -> b.size() > 0)
            .findFirst()
            .orElseThrow(AssertionError::new);
        assertEquals(1, bucket.size());

        bufferPool.release(buffer2);
        assertEquals(2, bucket.size());

        bufferPool.release(buffer3);
        assertEquals(2, bucket.size());
    }

    @Test
    public void testMaxMemory()
    {
        int factor = 1024;
        int maxMemory = 11 * factor;
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(-1, factor, -1, -1, -1, maxMemory);
        Bucket[] buckets = bufferPool.bucketsFor(true);

        // Create the buckets - the oldest is the larger.
        // 1+2+3+4=10 / maxMemory=11.
        for (int i = 4; i >= 1; --i)
        {
            int capacity = factor * i;
            ByteBuffer buffer = bufferPool.acquire(capacity, true);
            assertThat(buffer.capacity(), equalTo(capacity));
            bufferPool.release(buffer);
        }

        // Check state of buckets.
        assertThat(bufferPool.getMemory(true), equalTo(10L * factor));
        assertThat(buckets[1].size(), equalTo(1));
        assertThat(buckets[2].size(), equalTo(1));
        assertThat(buckets[3].size(), equalTo(1));
        assertThat(buckets[4].size(), equalTo(1));

        // Create and release a buffer to exceed the max memory.
        int capacity = 2 * factor;
        ByteBuffer buffer = bufferPool.newByteBuffer(capacity, true);
        assertThat(buffer.capacity(), equalTo(capacity));
        bufferPool.release(buffer);

        // Now the oldest buffer should be gone and we have: 1+2x2+3=8
        assertThat(bufferPool.getMemory(true), equalTo(8L * factor));
        assertThat(buckets[1].size(), equalTo(1));
        assertThat(buckets[2].size(), equalTo(2));
        assertThat(buckets[3].size(), equalTo(1));

        // Create and release a large buffer.
        // Max memory is exceeded and buckets 3 and 1 are cleared.
        // We will have 2x2+7=11.
        capacity = 7 * factor;
        buffer = bufferPool.newByteBuffer(capacity, true);
        bufferPool.release(buffer);

        assertThat(bufferPool.getMemory(true), equalTo(11L * factor));
        assertThat(buckets[2].size(), equalTo(2));
        assertThat(buckets[7].size(), equalTo(1));
    }

    @Test
    public void testEndiannessResetOnRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool();
        ByteBuffer buffer = bufferPool.acquire(10, true);
        assertThat(buffer.order(), is(ByteOrder.BIG_ENDIAN));
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        bufferPool.release(buffer);
        assertThat(buffer.order(), is(ByteOrder.BIG_ENDIAN));
    }
}
