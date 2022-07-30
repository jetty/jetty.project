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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A ByteBuffer pool where ByteBuffers are held in queues that are held in array elements.</p>
 * <p>Given a capacity {@code factor} of 4096, the first array element holds a bucket of ByteBuffers
 * each of capacity 4096, the second array element holds a bucket of ByteBuffers each of capacity
 * 8192, and so on.</p>
 * <p>The {@code maxHeapMemory} and {@code maxDirectMemory} default heuristic is to use {@link Runtime#maxMemory()}
 * divided by 4.</p>
 */
@ManagedObject
public class ArrayByteBufferPool extends AbstractByteBufferPool implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ArrayByteBufferPool.class);

    private final int _maxCapacity;
    private final int _minCapacity;
    private final Bucket[] _direct;
    private final Bucket[] _indirect;
    private boolean _detailedDump = false;

    /**
     * Creates a new ArrayByteBufferPool with a default configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     */
    public ArrayByteBufferPool()
    {
        this(-1, -1, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity)
    {
        this(minCapacity, factor, maxCapacity, -1, 0, 0);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxQueueLength)
    {
        this(minCapacity, factor, maxCapacity, maxQueueLength, 0, 0);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum ByteBuffer queue length in a {@link Bucket}
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, maxHeapMemory, maxDirectMemory);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum ByteBuffer queue length in a {@link Bucket}
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param retainedHeapMemory the max heap memory in bytes, -2 for no retained memory, -1 for unlimited retained memory or 0 to use default heuristic
     * @param retainedDirectMemory the max direct memory in bytes, -2 for no retained memory, -1 for unlimited retained memory or 0 to use default heuristic
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory, long retainedHeapMemory, long retainedDirectMemory)
    {
        super(factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, retainedHeapMemory, retainedDirectMemory);
        maxCapacity = getMaxCapacity();

        factor = getCapacityFactor();
        if (minCapacity <= 0)
            minCapacity = 0;
        if ((maxCapacity % factor) != 0 || factor >= maxCapacity)
            throw new IllegalArgumentException("The capacity factor must be a divisor of maxCapacity");
        _maxCapacity = maxCapacity;
        _minCapacity = minCapacity;

        // Initialize all buckets in constructor and never modify the array again.
        int length = bucketFor(maxCapacity) + 1;
        _direct = new Bucket[length];
        _indirect = new Bucket[length];
        for (int i = 0; i < length; i++)
        {
            _direct[i] = newBucket(i, true);
            _indirect[i] = newBucket(i, false);
        }
    }

    @Override
    protected RetainableByteBufferPool newRetainableByteBufferPool(int factor, int maxCapacity, int maxBucketSize, long retainedHeapMemory, long retainedDirectMemory)
    {
        return new Retained(factor, maxCapacity, maxBucketSize, retainedHeapMemory, retainedDirectMemory);
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        int capacity = size < _minCapacity ? size : capacityFor(bucketFor(size));
        Bucket bucket = bucketFor(size, direct);
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
            return;

        int capacity = buffer.capacity();
        // Validate that this buffer is from this pool.
        if (capacity != capacityFor(bucketFor(capacity)))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ByteBuffer {} does not belong to this pool, discarding it", BufferUtil.toDetailString(buffer));
            return;
        }

        // Don't release into the pool if greater than the maximum ByteBuffer capacity.
        if (capacity > _maxCapacity)
            return;

        boolean direct = buffer.isDirect();
        Bucket bucket = bucketFor(capacity, direct);
        if (bucket != null)
        {
            bucket.release(buffer);
            releaseExcessMemory(direct, this::releaseMemory);
        }
    }

    private Bucket newBucket(int key, boolean direct)
    {
        return new Bucket(capacityFor(key), getMaxBucketSize(), updateMemory(direct));
    }

    @Override
    public void clear()
    {
        super.clear();
        for (int i = 0; i < _direct.length; ++i)
        {
            _direct[i].clear();
            _indirect[i].clear();
        }
    }

    protected void releaseMemory(boolean direct)
    {
        long oldest = Long.MAX_VALUE;
        int index = -1;
        Bucket[] buckets = bucketsFor(direct);
        for (int i = 0; i < buckets.length; ++i)
        {
            Bucket bucket = buckets[i];
            if (bucket.isEmpty())
                continue;
            long lastUpdate = bucket.getLastUpdate();
            if (lastUpdate < oldest)
            {
                oldest = lastUpdate;
                index = i;
            }
        }
        if (index >= 0)
        {
            Bucket bucket = buckets[index];
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

    protected Bucket bucketFor(int capacity, boolean direct)
    {
        if (capacity < _minCapacity)
            return null;
        int bucket = bucketFor(capacity);
        if (bucket >= _direct.length)
            return null;
        Bucket[] buckets = bucketsFor(direct);
        return buckets[bucket];
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
        return Arrays.stream(bucketsFor(direct))
            .filter(Objects::nonNull)
            .mapToLong(Bucket::size)
            .sum();
    }

    // Package local for testing
    Bucket[] bucketsFor(boolean direct)
    {
        return direct ? _direct : _indirect;
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

        List<Bucket> indirect = Arrays.stream(_indirect).filter(b -> !b.isEmpty()).collect(Collectors.toList());
        List<Bucket> direct = Arrays.stream(_direct).filter(b -> !b.isEmpty()).collect(Collectors.toList());
        if (isDetailedDump())
        {
            dump.add(new DumpableCollection("Indirect Buckets", indirect));
            dump.add(new DumpableCollection("Direct Buckets", direct));
        }
        else
        {
            dump.add("Indirect Buckets size=" + indirect.size());
            dump.add("Direct Buckets size=" + direct.size());
        }
        dump.add(asRetainableByteBufferPool());
        Dumpable.dumpObjects(out, indent, this, dump);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{minBufferCapacity=%s, maxBufferCapacity=%s, maxQueueLength=%s, factor=%s}",
            this.getClass().getSimpleName(), hashCode(),
            _minCapacity,
            _maxCapacity,
            getMaxBucketSize(),
            getCapacityFactor());
    }

    protected class Retained extends ArrayRetainableByteBufferPool
    {
        public Retained(int factor, int maxCapacity, int maxBucketSize, long retainedHeapMemory, long retainedDirectMemory)
        {
            super(0, factor, maxCapacity, maxBucketSize, retainedHeapMemory, retainedDirectMemory);
        }

        @Override
        protected ByteBuffer allocate(int capacity)
        {
            return ArrayByteBufferPool.this.acquire(capacity, false);
        }

        @Override
        protected ByteBuffer allocateDirect(int capacity)
        {
            return ArrayByteBufferPool.this.acquire(capacity, true);
        }

        @Override
        protected void removed(RetainableByteBuffer retainedBuffer)
        {
            ArrayByteBufferPool.this.release(retainedBuffer.getBuffer());
        }
    }
}
