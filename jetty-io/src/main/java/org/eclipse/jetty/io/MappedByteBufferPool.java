//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

/**
 * <p>A ByteBuffer pool where ByteBuffers are held in queues that are held in a Map.</p>
 * <p>Given a capacity {@code factor} of 1024, the Map entry with key {@code 1} holds a
 * queue of ByteBuffers each of capacity 1024, the Map entry with key {@code 2} holds a
 * queue of ByteBuffers each of capacity 2048, and so on.</p>
 */
@ManagedObject
public class MappedByteBufferPool implements ByteBufferPool
{
    private final ConcurrentMap<Integer, Bucket> directBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Bucket> heapBuffers = new ConcurrentHashMap<>();
    private final int _factor;
    private final int _maxQueueLength;

    /**
     * Creates a new MappedByteBufferPool with a default configuration.
     */
    public MappedByteBufferPool()
    {
        this(-1);
    }

    /**
     * Creates a new MappedByteBufferPool with the given capacity factor.
     *
     * @param factor the capacity factor
     */
    public MappedByteBufferPool(int factor)
    {
        this(factor, -1);
    }

    /**
     * Creates a new MappedByteBufferPool with the given capacity factor and max queue length.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     */
    public MappedByteBufferPool(int factor, int maxQueueLength)
    {
        _factor = factor <= 0 ? 1024 : factor;
        _maxQueueLength = maxQueueLength;
    }

    private Bucket newBucket(int key)
    {
        return new Bucket(this, key * _factor, _maxQueueLength);
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        int b = bucketFor(size);
        ConcurrentMap<Integer, Bucket> buffers = bucketsFor(direct);
        Bucket bucket = buffers.get(b);
        if (bucket == null)
            return newByteBuffer(b * _factor, direct);
        return bucket.acquire(direct);
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return; // nothing to do

        // validate that this buffer is from this pool
        assert ((buffer.capacity() % _factor) == 0);

        int b = bucketFor(buffer.capacity());
        ConcurrentMap<Integer, Bucket> buckets = bucketsFor(buffer.isDirect());

        Bucket bucket = buckets.computeIfAbsent(b, this::newBucket);
        bucket.release(buffer);
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        directBuffers.values().forEach(Bucket::clear);
        directBuffers.clear();
        heapBuffers.values().forEach(Bucket::clear);
        heapBuffers.clear();
    }

    private int bucketFor(int size)
    {
        int bucket = size / _factor;
        if (size % _factor > 0)
            ++bucket;
        return bucket;
    }

    // Package local for testing
    ConcurrentMap<Integer, Bucket> bucketsFor(boolean direct)
    {
        return direct ? directBuffers : heapBuffers;
    }

    public static class Tagged extends MappedByteBufferPool
    {
        private final AtomicInteger tag = new AtomicInteger();

        @Override
        public ByteBuffer newByteBuffer(int capacity, boolean direct)
        {
            ByteBuffer buffer = super.newByteBuffer(capacity + 4, direct);
            buffer.limit(buffer.capacity());
            buffer.putInt(tag.incrementAndGet());
            ByteBuffer slice = buffer.slice();
            BufferUtil.clear(slice);
            return slice;
        }
    }
}
