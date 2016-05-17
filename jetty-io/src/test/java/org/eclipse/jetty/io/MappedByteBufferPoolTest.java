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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.io.ByteBufferPool.Bucket;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Test;

public class MappedByteBufferPoolTest
{
    @Test
    public void testAcquireRelease() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer,Bucket> buckets = bufferPool.bucketsFor(true);

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
    public void testAcquireReleaseAcquire() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer,Bucket> buckets = bufferPool.bucketsFor(false);

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
    public void testAcquireReleaseClear() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer,Bucket> buckets = bufferPool.bucketsFor(true);

        ByteBuffer buffer = bufferPool.acquire(512, true);
        bufferPool.release(buffer);

        assertEquals(1, buckets.size());
        assertEquals(1, buckets.values().iterator().next().size());

        bufferPool.clear();

        assertTrue(buckets.isEmpty());
    }
    
    /**
     * In a scenario where MappedByteBufferPool is being used improperly, such as releasing a buffer that wasn't created/acquired by the MappedByteBufferPool,
     * an assertion is tested for.
     * @throws Exception test failure
     */
    @Test
    public void testReleaseAssertion() throws Exception
    {
        int factor = 1024;
        MappedByteBufferPool bufferPool = new MappedByteBufferPool(factor);

        try
        {
            // Release a few small non-pool buffers
            bufferPool.release(ByteBuffer.wrap(StringUtil.getUtf8Bytes("Hello")));

            /* NOTES: 
             * 
             * 1) This test will pass on command line maven build, as its surefire setup uses "-ea" already.
             * 2) In Eclipse, goto the "Run Configuration" for this test case.
             *    Select the "Arguments" tab, and make sure "-ea" is present in the text box titled "VM arguments"
             */
            fail("Expected java.lang.AssertionError, do you have '-ea' JVM command line option enabled?");
        }
        catch (java.lang.AssertionError e)
        {
            // Expected path.
        }
    }
    
    @Test
    public void testTagged()
    {
        MappedByteBufferPool pool = new MappedByteBufferPool.Tagged();

        ByteBuffer buffer = pool.acquire(1024,false);

        assertThat(BufferUtil.toDetailString(buffer),containsString("@T00000001"));
        buffer = pool.acquire(1024,false);
        assertThat(BufferUtil.toDetailString(buffer),containsString("@T00000002"));
    }
    


    @Test
    public void testMaxQueue() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool(-1,2);
        ConcurrentMap<Integer,Bucket> buckets = bufferPool.bucketsFor(false);

        ByteBuffer buffer1 = bufferPool.acquire(512, false);
        ByteBuffer buffer2 = bufferPool.acquire(512, false);
        ByteBuffer buffer3 = bufferPool.acquire(512, false);
        assertEquals(0, buckets.size());

        bufferPool.release(buffer1);
        assertEquals(1, buckets.size());
        Bucket bucket=buckets.values().iterator().next();
        assertEquals(1, bucket.size());

        bufferPool.release(buffer2);
        assertEquals(2, bucket.size());
        
        bufferPool.release(buffer3);
        assertEquals(2, bucket.size());

    }
}
