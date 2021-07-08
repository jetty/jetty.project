//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DefaultRetainableByteBufferPoolTest
{
    @Test
    public void testMaxMemoryEviction()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(0, 10, 20, Integer.MAX_VALUE, 40, 40);

        RetainableByteBuffer buf1 = pool.acquire(10, true);
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        RetainableByteBuffer buf2 = pool.acquire(10, true);
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        RetainableByteBuffer buf3 = pool.acquire(20, true);
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        RetainableByteBuffer buf4 = pool.acquire(20, true);
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));

        assertThat(pool.getAvailableDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getDirectMemory(), greaterThan(0L));

        buf1.release();
        buf2.release();
        buf3.release();
        buf4.release();

        assertThat(pool.getAvailableDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getAvailableDirectByteBufferCount(), lessThan(4L));
        assertThat(pool.getDirectByteBufferCount(), greaterThan(0L));
        assertThat(pool.getDirectByteBufferCount(), lessThan(4L));
        assertThat(pool.getDirectMemory(), lessThanOrEqualTo(40L));
        assertThat(pool.getDirectMemory(), greaterThan(0L));
    }

    @Test
    void testBelowMinCapacityDoesNotPool()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(1, true);
        assertThat(buf1.capacity(), is(1));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buf1.release();
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
    }

    @Test
    void testOverMaxCapacityDoesNotPool()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

        RetainableByteBuffer buf1 = pool.acquire(21, true);
        assertThat(buf1.capacity(), is(21));
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));

        buf1.release();
        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
    }

    @Test
    void testTooManyReleases()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(0, 10, 20, 2);

        pool.acquire(1, true);  // pooled
        pool.acquire(1, true);  // pooled
        pool.acquire(1, true);  // not pooled, bucket is full

        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(20L));

        pool.acquire(11, true);  // pooled
        pool.acquire(11, true);  // pooled
        pool.acquire(11, true);  // not pooled, bucket is full

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getDirectMemory(), is(60L));
    }

    @Test
    public void testBufferReleaseRepools()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(0, 10, 20, 1);

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
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(10, 10, 20, Integer.MAX_VALUE);

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
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool();

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
    public void testAcquireRelease()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool();

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

            RetainableByteBuffer buf3 = pool.acquire(16385, true);
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
}
