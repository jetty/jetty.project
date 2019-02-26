//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.ByteBufferPool.Bucket;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
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
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = 1; size <= 9; size++)
        {
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertEquals(size, buffer.capacity());
            for (ByteBufferPool.Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }

            bufferPool.release(buffer);

            for (ByteBufferPool.Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }
        }
    }

    @Test
    public void testMaxRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10, 100, 1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = 999; size <= 1001; size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
            for (ByteBufferPool.Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }

            bufferPool.release(buffer);

            int pooled = Arrays.stream(buckets)
                    .filter(Objects::nonNull)
                    .mapToInt(Bucket::size)
                    .sum();
            assertEquals(size <= 1000, 1 == pooled);
        }
    }

    @Test
    public void testAcquireRelease()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10, 100, 1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size = 390; size <= 510; size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
            for (ByteBufferPool.Bucket bucket : buckets)
            {
                if (bucket != null)
                    assertTrue(bucket.isEmpty());
            }

            bufferPool.release(buffer);

            int pooled = Arrays.stream(buckets)
                    .filter(Objects::nonNull)
                    .mapToInt(Bucket::size)
                    .sum();
            assertEquals(1, pooled);
        }
    }

    @Test
    public void testAcquireReleaseAcquire()
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10, 100, 1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

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
                    .mapToInt(Bucket::size)
                    .sum();
            assertEquals(1, pooled);

            assertSame(buffer1, buffer2);
            assertNotSame(buffer1, buffer3);
        }
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
        int maxMemory = 10 * 1024;
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(-1, factor, -1, -1, -1, maxMemory);

        int capacity = 3 * 1024;
        ByteBuffer[] buffers = new ByteBuffer[maxMemory / capacity + 1];
        for (int i = 0; i < buffers.length; ++i)
            buffers[i] = bufferPool.acquire(capacity, true);

        // Return all the buffers, but only some is retained by the pool.
        for (ByteBuffer buffer : buffers)
            bufferPool.release(buffer);

        List<Bucket> directBuckets = Arrays.stream(bufferPool.bucketsFor(true))
                .filter(Objects::nonNull)
                .filter(b -> !b.isEmpty())
                .collect(Collectors.toList());
        assertEquals(1, directBuckets.size());

        Bucket bucket = directBuckets.get(0);
        assertEquals(buffers.length - 1, bucket.size());

        long memory1 = bufferPool.getMemory(true);
        assertThat(memory1, lessThanOrEqualTo((long)maxMemory));

        ByteBuffer buffer = bufferPool.acquire(capacity, true);
        long memory2 = bufferPool.getMemory(true);
        assertThat(memory2, lessThan(memory1));

        bufferPool.release(buffer);
        long memory3 = bufferPool.getMemory(true);
        assertEquals(memory1, memory3);
    }
}
