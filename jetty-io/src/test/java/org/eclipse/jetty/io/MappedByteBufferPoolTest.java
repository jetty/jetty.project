//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.StringUtil;
import org.junit.Ignore;
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
     * In a scenario where BufferPool is being used, but some edges cases that use ByteBuffer.allocate()
     * and then later that buffer is released via the BufferPool, that non acquired buffer can contaminate
     * the buffer pool.
     */
    @Test
    public void testReleaseTiny() throws Exception
    {
        MappedByteBufferPool bufferPool = new MappedByteBufferPool();

        // Release a few small non-pool buffers
        bufferPool.release(ByteBuffer.wrap(StringUtil.getUtf8Bytes("Hello")));
        bufferPool.release(ByteBuffer.wrap(StringUtil.getUtf8Bytes("There")));
        
        // acquire small pool
        ByteBuffer small = bufferPool.acquire(35, false);
        assertThat(small.capacity(), greaterThanOrEqualTo(35));
        small.limit(35);
        bufferPool.release(small);
    }
}
