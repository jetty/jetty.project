//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.hamcrest.Matchers;
import org.junit.Test;

public class ArrayByteBufferPoolTest
{
    @Test
    public void testMinimumRelease() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ArrayByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=1;size<=9;size++)
        {
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertEquals(size,buffer.capacity());
            for (ArrayByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket._queue.isEmpty());

            bufferPool.release(buffer);

            for (ArrayByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket._queue.isEmpty());
        }
    }

    @Test
    public void testMaxRelease() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ArrayByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=999;size<=1001;size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(),greaterThanOrEqualTo(size));
            for (ArrayByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket._queue.isEmpty());

            bufferPool.release(buffer);

            int pooled=0;
            for (ArrayByteBufferPool.Bucket bucket : buckets)
            {
                pooled+=bucket._queue.size();
            }
            assertEquals(size<=1000,1==pooled);
        }
    }

    @Test
    public void testAcquireRelease() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ArrayByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=390;size<=510;size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
            for (ArrayByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket._queue.isEmpty());

            bufferPool.release(buffer);

            int pooled=0;
            for (ArrayByteBufferPool.Bucket bucket : buckets)
            {
                if (!bucket._queue.isEmpty())
                {
                    pooled+=bucket._queue.size();
                    assertThat(bucket._size,greaterThanOrEqualTo(size));
                    assertThat(bucket._size,Matchers.lessThan(size+100));
                }
            }
            assertEquals(1,pooled);
        }
    }

    @Test
    public void testAcquireReleaseAcquire() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ArrayByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=390;size<=510;size++)
        {
            bufferPool.clear();
            ByteBuffer buffer1 = bufferPool.acquire(size, true);
            bufferPool.release(buffer1);
            ByteBuffer buffer2 = bufferPool.acquire(size, true);
            bufferPool.release(buffer2);
            ByteBuffer buffer3 = bufferPool.acquire(size, false);
            bufferPool.release(buffer3);

            int pooled=0;
            for (ArrayByteBufferPool.Bucket bucket : buckets)
            {
                if (!bucket._queue.isEmpty())
                {
                    pooled+=bucket._queue.size();
                    assertThat(bucket._size,greaterThanOrEqualTo(size));
                    assertThat(bucket._size,Matchers.lessThan(size+100));
                }
            }
            assertEquals(1,pooled);

            assertTrue(buffer1==buffer2);
            assertTrue(buffer1!=buffer3);
        }
    }

}
