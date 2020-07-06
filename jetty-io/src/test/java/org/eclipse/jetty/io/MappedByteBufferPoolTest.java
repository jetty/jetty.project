//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.io.ByteBufferPool.Bucket;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MappedByteBufferPoolTest
{
    @Test
    public void testAcquireRelease()
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer, Bucket> buckets = bufferPool.bucketsFor(true);

        int size = 512;
        ByteBuffer buffer = bufferPool.acquire(size, true);

        assertTrue(buffer.isDirect());
        assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
        assertTrue(buckets.isEmpty());

        bufferPool.release(buffer);

        assertEquals(1, buckets.size());
        assertEquals(1, buckets.values().iterator().next().size());
    }

    @Test
    public void testAcquireReleaseAcquire()
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer, Bucket> buckets = bufferPool.bucketsFor(false);

        ByteBuffer buffer1 = bufferPool.acquire(512, false);
        bufferPool.release(buffer1);
        ByteBuffer buffer2 = bufferPool.acquire(512, false);

        assertSame(buffer1, buffer2);
        assertEquals(1, buckets.size());
        assertEquals(0, buckets.values().iterator().next().size());

        bufferPool.release(buffer2);

        assertEquals(1, buckets.size());
        assertEquals(1, buckets.values().iterator().next().size());
    }

    @Test
    public void testAcquireReleaseClear()
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer, Bucket> buckets = bufferPool.bucketsFor(true);

        ByteBuffer buffer = bufferPool.acquire(512, true);
        bufferPool.release(buffer);

        assertEquals(1, buckets.size());
        assertEquals(1, buckets.values().iterator().next().size());

        bufferPool.clear();

        assertTrue(buckets.isEmpty());
    }

    @Test
    public void testReleaseNonPooledBuffer()
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();

        // Release a few small non-pool buffers
        bufferPool.release(ByteBuffer.wrap(StringUtil.getUtf8Bytes("Hello")));

        assertEquals(0, bufferPool.getHeapByteBufferCount());
    }

    @Test
    public void testTagged()
    {
        MappedByteBufferPool pool = new MappedByteBufferPool.Tagged();

        ByteBuffer buffer = pool.acquire(1024, false);

        assertThat(BufferUtil.toDetailString(buffer), containsString("@T00000001"));
        buffer = pool.acquire(1024, false);
        assertThat(BufferUtil.toDetailString(buffer), containsString("@T00000002"));
    }

    @Test
    public void testMaxQueue()
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool(-1, 2);
        ConcurrentMap<Integer, Bucket> buckets = bufferPool.bucketsFor(false);

        ByteBuffer buffer1 = bufferPool.acquire(512, false);
        ByteBuffer buffer2 = bufferPool.acquire(512, false);
        ByteBuffer buffer3 = bufferPool.acquire(512, false);
        assertEquals(0, buckets.size());

        bufferPool.release(buffer1);
        assertEquals(1, buckets.size());
        Bucket bucket = buckets.values().iterator().next();
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
        int maxMemory = 11 * 1024;
        MappedByteBufferPool bufferPool = new MappedByteBufferPool(factor, -1, null, -1, maxMemory);
        ConcurrentMap<Integer, Bucket> buckets = bufferPool.bucketsFor(true);

        // Create the buckets - the oldest is the larger.
        // 1+2+3+4=10 / maxMemory=11.
        for (int i = 4; i >= 1; --i)
        {
            int capacity = factor * i;
            ByteBuffer buffer = bufferPool.acquire(capacity, true);
            bufferPool.release(buffer);
        }

        // Create and release a buffer to exceed the max memory.
        ByteBuffer buffer = bufferPool.newByteBuffer(2 * factor, true);
        bufferPool.release(buffer);

        // Now the oldest buffer should be gone and we have: 1+2x2+3=8
        long memory = bufferPool.getMemory(true);
        assertThat(memory, lessThan((long)maxMemory));
        assertNull(buckets.get(4));

        // Create and release a large buffer.
        // Max memory is exceeded and buckets 3 and 1 are cleared.
        // We will have 2x2+7=11.
        buffer = bufferPool.newByteBuffer(7 * factor, true);
        bufferPool.release(buffer);
        memory = bufferPool.getMemory(true);
        assertThat(memory, lessThanOrEqualTo((long)maxMemory));
        assertNull(buckets.get(1));
        assertNull(buckets.get(3));
    }
}
