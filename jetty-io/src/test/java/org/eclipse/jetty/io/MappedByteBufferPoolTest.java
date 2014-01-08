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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.StringUtil;
import org.junit.Test;

public class MappedByteBufferPoolTest
{
    @Test
    public void testAcquireRelease() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer,Queue<ByteBuffer>> buffers = bufferPool.buffersFor(true);

        int size = 512;
        ByteBuffer buffer = bufferPool.acquire(size, true);

        assertTrue(buffer.isDirect());
        assertThat(buffer.capacity(), greaterThanOrEqualTo(size));
        assertTrue(buffers.isEmpty());

        bufferPool.release(buffer);

        assertEquals(1, buffers.size());
    }

    @Test
    public void testAcquireReleaseAcquire() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer,Queue<ByteBuffer>> buffers = bufferPool.buffersFor(false);

        ByteBuffer buffer1 = bufferPool.acquire(512, false);
        bufferPool.release(buffer1);
        ByteBuffer buffer2 = bufferPool.acquire(512, false);

        assertSame(buffer1, buffer2);

        bufferPool.release(buffer2);

        assertEquals(1, buffers.size());
    }

    @Test
    public void testAcquireReleaseClear() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();
        ConcurrentMap<Integer,Queue<ByteBuffer>> buffers = bufferPool.buffersFor(true);

        ByteBuffer buffer = bufferPool.acquire(512, true);
        bufferPool.release(buffer);

        assertEquals(1, buffers.size());

        bufferPool.clear();

        assertTrue(buffers.isEmpty());
    }
    
    /**
     * In a scenario where MappedByteBufferPool is being used improperly, such as releasing a buffer that wasn't created/acquired by the MappedByteBufferPool,
     * an assertion is tested for.
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
}
