//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A ByteBuffer pool where ByteBuffers are held in queues that are held in a Map.</p>
 * <p>Given a capacity {@code factor} of 1024, the Map entry with key {@code 1} holds a
 * queue of ByteBuffers each of capacity 1024, the Map entry with key {@code 2} holds a
 * queue of ByteBuffers each of capacity 2048, and so on.</p>
 */
@ManagedObject
public class MappedByteBufferPool extends AbstractByteBufferPool implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(MappedByteBufferPool.class);

    private final ConcurrentMap<Integer, Bucket> _directBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Bucket> _heapBuffers = new ConcurrentHashMap<>();
    private final Function<Integer, Bucket> _newBucket;
    private boolean _detailedDump = false;

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
    private MappedByteBufferPool(int factor, int maxQueueLength, Function<Integer, Bucket> newBucket)
    {
        this(factor, maxQueueLength, newBucket, -1, -1, -1, -1);
    }

    /**
     * Creates a new MappedByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic.
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic.
     */
    public MappedByteBufferPool(int factor, int maxQueueLength, long maxHeapMemory, long maxDirectMemory)
    {
        this(factor, maxQueueLength, null, maxHeapMemory, maxDirectMemory, maxHeapMemory, maxDirectMemory);
    }

    /**
     * Creates a new MappedByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic.
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic.
     * @param retainedHeapMemory the max heap memory in bytes, -2 for no retained memory, -1 for unlimited retained memory or 0 to use default heuristic
     * @param retainedDirectMemory the max direct memory in bytes, -2 for no retained memory, -1 for unlimited retained memory or 0 to use default heuristic
     */
    public MappedByteBufferPool(int factor, int maxQueueLength, long maxHeapMemory, long maxDirectMemory, long retainedHeapMemory, long retainedDirectMemory)
    {
        this(factor, maxQueueLength, null, maxHeapMemory, maxDirectMemory, retainedHeapMemory, retainedDirectMemory);
    }
    
    /**
     * Creates a new MappedByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param newBucket the function that creates a Bucket
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic.
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic.
     * @param retainedHeapMemory the max heap memory in bytes, -2 for no retained memory, -1 for unlimited retained memory or 0 to use default heuristic
     * @param retainedDirectMemory the max direct memory in bytes, -2 for no retained memory, -1 for unlimited retained memory or 0 to use default heuristic
     */
    private MappedByteBufferPool(int factor, int maxQueueLength, Function<Integer, Bucket> newBucket, long maxHeapMemory, long maxDirectMemory, long retainedHeapMemory, long retainedDirectMemory)
    {
        super(factor, 0, maxQueueLength, maxHeapMemory, maxDirectMemory, retainedHeapMemory, retainedDirectMemory);
        _newBucket = newBucket;
    }

    private Bucket newBucket(int key, boolean direct)
    {
        return (_newBucket != null) ? _newBucket.apply(key) : new Bucket(capacityFor(key), getMaxBucketSize(), updateMemory(direct));
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        int b = bucketFor(size);
        int capacity = capacityFor(b);
        ConcurrentMap<Integer, Bucket> buffers = bucketsFor(direct);
        Bucket bucket = buffers.get(b);
        if (bucket == null)
            return newByteBuffer(capacity, direct);
        ByteBuffer buffer = bucket.acquire();
        if (buffer == null)
            return newByteBuffer(capacity, direct);
        return buffer;
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer == null)
            return; // nothing to do

        int capacity = buffer.capacity();
        int b = bucketFor(capacity);
        // Validate that this buffer is from this pool.
        if (capacity != capacityFor(b))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ByteBuffer {} does not belong to this pool, discarding it", BufferUtil.toDetailString(buffer));
            return;
        }

        boolean direct = buffer.isDirect();
        ConcurrentMap<Integer, Bucket> buckets = bucketsFor(direct);
        Bucket bucket = buckets.computeIfAbsent(b, i -> newBucket(i, direct));
        bucket.release(buffer);
        releaseExcessMemory(direct, this::releaseMemory);
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

    protected void releaseMemory(boolean direct)
    {
        long oldest = Long.MAX_VALUE;
        int index = -1;
        ConcurrentMap<Integer, Bucket> buckets = bucketsFor(direct);
        for (Map.Entry<Integer, Bucket> entry : buckets.entrySet())
        {
            Bucket bucket = entry.getValue();
            if (bucket.isEmpty())
                continue;

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
            // Null guard in case this.clear() is called concurrently.
            if (bucket != null)
                bucket.clear();
        }
    }

    protected int bucketFor(int capacity)
    {
        return (int)Math.ceil((double)capacity / getCapacityFactor());
    }

    protected int capacityFor(int bucket)
    {
        return bucket * getCapacityFactor();
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

    public boolean isDetailedDump()
    {
        return _detailedDump;
    }

    public void setDetailedDump(boolean detailedDump)
    {
        _detailedDump = detailedDump;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Object> dump = new ArrayList<>();
        dump.add(String.format("HeapMemory: %d/%d", getHeapMemory(), getMaxHeapMemory()));
        dump.add(String.format("DirectMemory: %d/%d", getDirectMemory(), getMaxDirectMemory()));

        if (isDetailedDump())
        {
            dump.add(new DumpableCollection("Indirect Buckets", _heapBuffers.values()));
            dump.add(new DumpableCollection("Direct Buckets", _directBuffers.values()));
        }
        else
        {
            dump.add("Indirect Buckets size=" + _heapBuffers.size());
            dump.add("Direct Buckets size=" + _directBuffers.size());
        }
        Dumpable.dumpObjects(out, indent, this, dump);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{maxQueueLength=%s, factor=%s}",
            this.getClass().getSimpleName(), hashCode(),
            getMaxBucketSize(),
            getCapacityFactor());
    }
}
