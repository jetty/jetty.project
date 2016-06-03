//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;

import org.eclipse.jetty.io.ByteBufferPool.Bucket;
import org.junit.Test;

public class ArrayByteBufferPoolTest
{
    @Test
    public void testMinimumRelease() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=1;size<=9;size++)
        {
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertEquals(size,buffer.capacity());
            for (ByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket.isEmpty());

            bufferPool.release(buffer);

            for (ByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket.isEmpty());
        }
    }

    @Test
    public void testMaxRelease() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=999;size<=1001;size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(),greaterThanOrEqualTo(size));
            for (ByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket.isEmpty());

            bufferPool.release(buffer);

            int pooled=0;
            for (ByteBufferPool.Bucket bucket : buckets)
            {
                pooled+=bucket.size();
            }
            assertEquals(size<=1000,1==pooled);
        }
    }

    @Test
    public void testAcquireRelease() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

        for (int size=390;size<=510;size++)
        {
            bufferPool.clear();
            ByteBuffer buffer = bufferPool.acquire(size, true);

            assertTrue(buffer.isDirect());
            assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
            for (ByteBufferPool.Bucket bucket : buckets)
                assertTrue(bucket.isEmpty());

            bufferPool.release(buffer);

            int pooled=0;
            for (ByteBufferPool.Bucket bucket : buckets)
            {
                if (!bucket.isEmpty())
                {
                    pooled+=bucket.size();
                    // TODO assertThat(bucket._bufferSize,greaterThanOrEqualTo(size));
                    // TODO assertThat(bucket._bufferSize,Matchers.lessThan(size+100));
                }
            }
            assertEquals(1,pooled);
        }
    }

    @Test
    public void testAcquireReleaseAcquire() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(10,100,1000);
        ByteBufferPool.Bucket[] buckets = bufferPool.bucketsFor(true);

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
            for (ByteBufferPool.Bucket bucket : buckets)
            {
                if (!bucket.isEmpty())
                {
                    pooled+=bucket.size();
                    // TODO assertThat(bucket._bufferSize,greaterThanOrEqualTo(size));
                    // TODO assertThat(bucket._bufferSize,Matchers.lessThan(size+100));
                }
            }
            assertEquals(1,pooled);

            assertTrue(buffer1==buffer2);
            assertTrue(buffer1!=buffer3);
        }
    }
    

    @Test
    public void testMaxQueue() throws Exception
    {
        ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(-1,-1,-1,2);

        ByteBuffer buffer1 = bufferPool.acquire(512, false);
        ByteBuffer buffer2 = bufferPool.acquire(512, false);
        ByteBuffer buffer3 = bufferPool.acquire(512, false);

        Bucket[] buckets = bufferPool.bucketsFor(false);
        Arrays.asList(buckets).forEach(b->assertEquals(0,b.size()));
        
        bufferPool.release(buffer1);
        Bucket bucket=Arrays.asList(buckets).stream().filter(b->b.size()>0).findFirst().get();
        assertEquals(1, bucket.size());

        bufferPool.release(buffer2);
        assertEquals(2, bucket.size());
        
        bufferPool.release(buffer3);
        assertEquals(2, bucket.size());
    }

}
