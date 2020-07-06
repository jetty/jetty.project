//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A ByteBuffer pool where ByteBuffers are held in queues that are held in a Map.</p>
 * <p>Given a capacity {@code factor} of 1024, the Map entry with key {@code 1} holds a
 * queue of ByteBuffers each of capacity 1024, the Map entry with key {@code 2} holds a
 * queue of ByteBuffers each of capacity 2048, and so on.</p>
 */
@ManagedObject
public class MappedByteBufferPool extends AbstractByteBufferPool
{
    private static final Logger LOG = LoggerFactory.getLogger(MappedByteBufferPool.class);

    private final ConcurrentMap<Integer, Bucket> _directBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Bucket> _heapBuffers = new ConcurrentHashMap<>();
    private final Function<Integer, Bucket> _newBucket;

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
     * Creates a new MappedByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     */
    public MappedByteBufferPool(int factor, int maxQueueLength)
    {
        this(factor, maxQueueLength, null);
    }

    /**
     * Creates a new MappedByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param newBucket the function that creates a Bucket
     */
    public MappedByteBufferPool(int factor, int maxQueueLength, Function<Integer, Bucket> newBucket)
    {
        this(factor, maxQueueLength, newBucket, -1, -1);
    }

    /**
     * Creates a new MappedByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param newBucket the function that creates a Bucket
     * @param maxHeapMemory the max heap memory in bytes
     * @param maxDirectMemory the max direct memory in bytes
     */
    public MappedByteBufferPool(int factor, int maxQueueLength, Function<Integer, Bucket> newBucket, long maxHeapMemory, long maxDirectMemory)
    {
        super(factor, maxQueueLength, maxHeapMemory, maxDirectMemory);
        _newBucket = newBucket != null ? newBucket : this::newBucket;
    }

    private Bucket newBucket(int key)
    {
        return new Bucket(key * getCapacityFactor(), getMaxQueueLength());
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        int b = bucketFor(size);
        int capacity = b * getCapacityFactor();
        ConcurrentMap<Integer, Bucket> buffers = bucketsFor(direct);
        Bucket bucket = buffers.get(b);
        if (bucket == null)
            return newByteBuffer(capacity, direct);
        ByteBuffer buffer = bucket.acquire();
        if (buffer == null)
            return newByteBuffer(capacity, direct);
        decrementMemory(buffer);
        return buffer;
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return; // nothing to do

        int capacity = buffer.capacity();
        // Validate that this buffer is from this pool.
        if ((capacity % getCapacityFactor()) != 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ByteBuffer {} does not belong to this pool, discarding it", BufferUtil.toDetailString(buffer));
            return;
        }

        int b = bucketFor(capacity);
        boolean direct = buffer.isDirect();
        ConcurrentMap<Integer, Bucket> buckets = bucketsFor(direct);
        Bucket bucket = buckets.computeIfAbsent(b, _newBucket);
        bucket.release(buffer);
        incrementMemory(buffer);
        releaseExcessMemory(direct, this::clearOldestBucket);
    }

    @Override
    public void clear()
    {
        super.clear();
        _directBuffers.values().forEach(Bucket::clear);
        _directBuffers.clear();
        _heapBuffers.values().forEach(Bucket::clear);
        _heapBuffers.clear();
    }

    private void clearOldestBucket(boolean direct)
    {
        long oldest = Long.MAX_VALUE;
        int index = -1;
        ConcurrentMap<Integer, Bucket> buckets = bucketsFor(direct);
        for (Map.Entry<Integer, Bucket> entry : buckets.entrySet())
        {
            Bucket bucket = entry.getValue();
            long lastUpdate = bucket.getLastUpdate();
            if (lastUpdate < oldest)
            {
                oldest = lastUpdate;
                index = entry.getKey();
            }
        }
        if (index >= 0)
        {
            Bucket bucket = buckets.remove(index);
            // The same bucket may be concurrently
            // removed, so we need this null guard.
            if (bucket != null)
                bucket.clear(this::decrementMemory);
        }
    }

    private int bucketFor(int size)
    {
        int factor = getCapacityFactor();
        int bucket = size / factor;
        if (bucket * factor != size)
            ++bucket;
        return bucket;
    }

    @ManagedAttribute("The number of pooled direct ByteBuffers")
    public long getDirectByteBufferCount()
    {
        return getByteBufferCount(true);
    }

    @ManagedAttribute("The number of pooled heap ByteBuffers")
    public long getHeapByteBufferCount()
    {
        return getByteBufferCount(false);
    }

    private long getByteBufferCount(boolean direct)
    {
        return bucketsFor(direct).values().stream()
            .mapToLong(Bucket::size)
            .sum();
    }

    // Package local for testing
    ConcurrentMap<Integer, Bucket> bucketsFor(boolean direct)
    {
        return direct ? _directBuffers : _heapBuffers;
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
