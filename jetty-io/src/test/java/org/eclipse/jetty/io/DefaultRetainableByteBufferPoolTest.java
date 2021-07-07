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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class DefaultRetainableByteBufferPoolTest
{
    @Test
    public void testSimpleMaxMemoryEviction() throws Exception
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(0, 10, 20, Integer.MAX_VALUE, 30, 30);

        RetainableByteBuffer buf1 = pool.acquire(1, true);
        Thread.sleep(10);
        RetainableByteBuffer buf2 = pool.acquire(1, true);
        Thread.sleep(10);
        RetainableByteBuffer buf3 = pool.acquire(11, true);

        buf1.release();
        buf2.release();
        buf3.release();

        // Make sure buf1 got evicted as it is the oldest one.

        assertThat(pool.getAvailableDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectByteBufferCount(), is(2L));
        assertThat(pool.getDirectMemory(), is(30L));

        RetainableByteBuffer buf4 = pool.acquire(1, true);
        assertThat(buf4 == buf2, is(true));
        RetainableByteBuffer buf5 = pool.acquire(11, true);
        assertThat(buf5 == buf3, is(true));
    }

    @Test
    public void testMaxMemoryEviction() throws Exception
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool(0, 10, 20, Integer.MAX_VALUE, 30, 30);

        RetainableByteBuffer buf1 = pool.acquire(1, false);
        Thread.sleep(10);
        RetainableByteBuffer buf2 = pool.acquire(1, false);
        Thread.sleep(10);
        RetainableByteBuffer buf3 = pool.acquire(11, false); // evicts buf1
        Thread.sleep(10);

        buf3.release();
        Thread.sleep(10);
        buf2.release();
        Thread.sleep(10);
        buf1.release();

        RetainableByteBuffer buf4 = pool.acquire(1, false); // == buf2
        RetainableByteBuffer buf5 = pool.acquire(11, false); // == buf3
        RetainableByteBuffer buf6 = pool.acquire(11, false); // Evicts buf3 as it was the one released the longest ago.

        buf4.release();
        buf5.release();
        buf6.release();

        // Make sure buf1 and buf3 got evicted and only buf2 and buf6 are left.

        assertThat(pool.getAvailableHeapByteBufferCount(), is(2L));
        assertThat(pool.getHeapByteBufferCount(), is(2L));
        assertThat(pool.getHeapMemory(), is(30L));

        RetainableByteBuffer buf7 = pool.acquire(1, false);
        assertThat(buf7 == buf2, is(true));
        RetainableByteBuffer buf8 = pool.acquire(11, false);
        assertThat(buf8 == buf6, is(true));
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
            {
                RetainableByteBuffer buffer = pool.acquire(10, true);
                assertThat(buffer, is(notNullValue()));
                RetainableByteBuffer buffer2 = pool.acquire(10, true);
                assertThat(buffer2, is(notNullValue()));
                buffer.release();
                buffer2.release();
            }
            {
                RetainableByteBuffer buffer = pool.acquire(16385, true);
                assertThat(buffer, is(notNullValue()));
                buffer.release();
            }
            {
                RetainableByteBuffer buffer = pool.acquire(32768, true);
                assertThat(buffer, is(notNullValue()));
                buffer.release();
            }
            {
                RetainableByteBuffer buffer = pool.acquire(32768, false);
                assertThat(buffer, is(notNullValue()));
                buffer.release();
            }
        }

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getHeapByteBufferCount(), is(1L));
        assertThat(pool.getDirectMemory(), is(52224L));
        assertThat(pool.getHeapMemory(), is(32768L));

        pool.clear();

        assertThat(pool.getDirectByteBufferCount(), is(0L));
        assertThat(pool.getHeapByteBufferCount(), is(0L));
        assertThat(pool.getDirectMemory(), is(0L));
        assertThat(pool.getHeapMemory(), is(0L));
    }
}
