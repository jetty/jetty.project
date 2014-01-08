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

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.BufferUtil;

public class MappedByteBufferPool implements ByteBufferPool
{
    private final ConcurrentMap<Integer, Queue<ByteBuffer>> directBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Queue<ByteBuffer>> heapBuffers = new ConcurrentHashMap<>();
    private final int factor;

    public MappedByteBufferPool()
    {
        this(1024);
    }

    public MappedByteBufferPool(int factor)
    {
        this.factor = factor;
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        int bucket = bucketFor(size);
        ConcurrentMap<Integer, Queue<ByteBuffer>> buffers = buffersFor(direct);

        ByteBuffer result = null;
        Queue<ByteBuffer> byteBuffers = buffers.get(bucket);
        if (byteBuffers != null)
            result = byteBuffers.poll();

        if (result == null)
        {
            int capacity = bucket * factor;
            result = direct ? BufferUtil.allocateDirect(capacity) : BufferUtil.allocate(capacity);
        }

        BufferUtil.clear(result);
        return result;
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return; // nothing to do
        
        // validate that this buffer is from this pool
        assert((buffer.capacity() % factor) == 0);
        
        int bucket = bucketFor(buffer.capacity());
        ConcurrentMap<Integer, Queue<ByteBuffer>> buffers = buffersFor(buffer.isDirect());

        // Avoid to create a new queue every time, just to be discarded immediately
        Queue<ByteBuffer> byteBuffers = buffers.get(bucket);
        if (byteBuffers == null)
        {
            byteBuffers = new ConcurrentLinkedQueue<>();
            Queue<ByteBuffer> existing = buffers.putIfAbsent(bucket, byteBuffers);
            if (existing != null)
                byteBuffers = existing;
        }

        BufferUtil.clear(buffer);
        byteBuffers.offer(buffer);
    }

    public void clear()
    {
        directBuffers.clear();
        heapBuffers.clear();
    }

    private int bucketFor(int size)
    {
        int bucket = size / factor;
        if (size % factor > 0)
            ++bucket;
        return bucket;
    }

    // Package local for testing
    ConcurrentMap<Integer, Queue<ByteBuffer>> buffersFor(boolean direct)
    {
        return direct ? directBuffers : heapBuffers;
    }
}
