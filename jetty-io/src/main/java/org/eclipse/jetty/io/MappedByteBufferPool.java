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

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class MappedByteBufferPool implements ByteBufferPool
{
    private static final Logger LOG = Log.getLogger(MappedByteBufferPool.class);
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
        if(LOG.isDebugEnabled()) {
            LOG.debug("acquire({}, {}) -> {}", size, direct, BufferUtil.toDetailString(result));
        }
        return result;
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if(LOG.isDebugEnabled()) {
            LOG.debug("release({})", BufferUtil.toDetailString(buffer));
        }
        
        if (buffer == null)
            return; // nothing to do
        
        if (buffer.capacity() < factor)
            return; // don't bother keeping track of this buffer (it obviously didn't come from this bytebuffer pool

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
