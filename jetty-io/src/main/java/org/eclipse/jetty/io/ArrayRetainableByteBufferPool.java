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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link RetainableByteBuffer} pool where RetainableByteBuffers are held in {@link Pool}s that are
 * held in array elements.</p>
 * <p>Given a capacity {@code factor} of 1024, the first array element holds a Pool of RetainableByteBuffers
 * each of capacity 1024, the second array element holds a Pool of RetainableByteBuffers each of capacity
 * 2048, and so on.</p>
 * <p>The {@code maxHeapMemory} and {@code maxDirectMemory} default heuristic is to use {@link Runtime#maxMemory()}
 * divided by 4.</p>
 */
@SuppressWarnings("resource")
@ManagedObject
public class ArrayRetainableByteBufferPool implements RetainableByteBufferPool, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ArrayRetainableByteBufferPool.class);

    private final RetainedBucket[] _direct;
    private final RetainedBucket[] _indirect;
    private final int _minCapacity;
    private final int _maxCapacity;
    private final long _maxHeapMemory;
    private final long _maxDirectMemory;
    private final AtomicLong _currentHeapMemory = new AtomicLong();
    private final AtomicLong _currentDirectMemory = new AtomicLong();
    private final Function<Integer, Integer> _bucketIndexFor;

    /**
     * Creates a new ArrayRetainableByteBufferPool with a default configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     */
    public ArrayRetainableByteBufferPool()
    {
        this(0, -1, -1, Integer.MAX_VALUE);
    }

    /**
     * Creates a new ArrayRetainableByteBufferPool with the given configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum number of ByteBuffers for each bucket
     */
    public ArrayRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, 0L, 0L);
    }

    /**
     * Creates a new ArrayRetainableByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum number of ByteBuffers for each bucket
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     */
    public ArrayRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, null, null);
    }

    /**
     * Creates a new ArrayRetainableByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum number of ByteBuffers for each bucket
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param bucketIndexFor a {@link Function} that takes a capacity and returns a bucket index
     * @param bucketCapacity a {@link Function} that takes a bucket index and returns a capacity
     */
    protected ArrayRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory,
                                            Function<Integer, Integer> bucketIndexFor, Function<Integer, Integer> bucketCapacity)
    {
        if (minCapacity <= 0)
            minCapacity = 0;
        factor = factor <= 0 ? AbstractByteBufferPool.DEFAULT_FACTOR : factor;
        if (maxCapacity <= 0)
            maxCapacity = AbstractByteBufferPool.DEFAULT_MAX_CAPACITY_BY_FACTOR * factor;
        if ((maxCapacity % factor) != 0 || factor >= maxCapacity)
            throw new IllegalArgumentException(String.format("The capacity factor(%d) must be a divisor of maxCapacity(%d)", factor, maxCapacity));

        int f = factor;
        if (bucketIndexFor == null)
            bucketIndexFor = c -> (c - 1) / f;
        if (bucketCapacity == null)
            bucketCapacity = i -> (i + 1) * f;

        int length = bucketIndexFor.apply(maxCapacity) + 1;
        RetainedBucket[] directArray = new RetainedBucket[length];
        RetainedBucket[] indirectArray = new RetainedBucket[length];
        for (int i = 0; i < directArray.length; i++)
        {
            int capacity = Math.min(bucketCapacity.apply(i), maxCapacity);
            directArray[i] = new RetainedBucket(capacity, maxBucketSize);
            indirectArray[i] = new RetainedBucket(capacity, maxBucketSize);
        }

        _minCapacity = minCapacity;
        _maxCapacity = maxCapacity;
        _direct = directArray;
        _indirect = indirectArray;
        _maxHeapMemory = AbstractByteBufferPool.retainedSize(maxHeapMemory);
        _maxDirectMemory = AbstractByteBufferPool.retainedSize(maxDirectMemory);
        _bucketIndexFor = bucketIndexFor;
    }

    @ManagedAttribute("The minimum pooled buffer capacity")
    public int getMinCapacity()
    {
        return _minCapacity;
    }

    @ManagedAttribute("The maximum pooled buffer capacity")
    public int getMaxCapacity()
    {
        return _maxCapacity;
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        RetainedBucket bucket = bucketFor(size, direct);
        if (bucket == null)
            return newRetainableByteBuffer(size, direct, this::removed);
        RetainedBucket.Entry entry = bucket.acquire();

        RetainableByteBuffer buffer;
        if (entry == null)
        {
            RetainedBucket.Entry reservedEntry = bucket.reserve();
            if (reservedEntry != null)
            {
                buffer = newRetainableByteBuffer(bucket._capacity, direct, retainedBuffer ->
                {
                    BufferUtil.reset(retainedBuffer.getBuffer());
                    reservedEntry.release();
                });
                reservedEntry.enable(buffer, true);
                if (direct)
                    _currentDirectMemory.addAndGet(buffer.capacity());
                else
                    _currentHeapMemory.addAndGet(buffer.capacity());
                releaseExcessMemory(direct);
            }
            else
            {
                buffer = newRetainableByteBuffer(size, direct, this::removed);
            }
        }
        else
        {
            buffer = entry.getPooled();
            buffer.acquire();
        }
        return buffer;
    }

    protected ByteBuffer allocate(int capacity)
    {
        return ByteBuffer.allocate(capacity);
    }

    protected ByteBuffer allocateDirect(int capacity)
    {
        return ByteBuffer.allocateDirect(capacity);
    }

    protected void removed(RetainableByteBuffer retainedBuffer)
    {
    }

    private RetainableByteBuffer newRetainableByteBuffer(int capacity, boolean direct, Consumer<RetainableByteBuffer> releaser)
    {
        ByteBuffer buffer = direct ? allocateDirect(capacity) : allocate(capacity);
        BufferUtil.clear(buffer);
        RetainableByteBuffer retainableByteBuffer = new RetainableByteBuffer(buffer, releaser);
        retainableByteBuffer.acquire();
        return retainableByteBuffer;
    }

    protected Pool<RetainableByteBuffer> poolFor(int capacity, boolean direct)
    {
        return bucketFor(capacity, direct);
    }

    private RetainedBucket bucketFor(int capacity, boolean direct)
    {
        if (capacity < _minCapacity)
            return null;
        int idx = _bucketIndexFor.apply(capacity);
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        if (idx >= buckets.length)
            return null;
        return buckets[idx];
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
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(RetainedBucket::size).sum();
    }

    @ManagedAttribute("The number of pooled direct ByteBuffers that are available")
    public long getAvailableDirectByteBufferCount()
    {
        return getAvailableByteBufferCount(true);
    }

    @ManagedAttribute("The number of pooled heap ByteBuffers that are available")
    public long getAvailableHeapByteBufferCount()
    {
        return getAvailableByteBufferCount(false);
    }

    private long getAvailableByteBufferCount(boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(bucket -> bucket.values().stream().filter(Pool.Entry::isIdle).count()).sum();
    }

    @ManagedAttribute("The bytes retained by direct ByteBuffers")
    public long getDirectMemory()
    {
        return getMemory(true);
    }

    @ManagedAttribute("The bytes retained by heap ByteBuffers")
    public long getHeapMemory()
    {
        return getMemory(false);
    }

    private long getMemory(boolean direct)
    {
        if (direct)
            return _currentDirectMemory.get();
        else
            return _currentHeapMemory.get();
    }

    @ManagedAttribute("The available bytes retained by direct ByteBuffers")
    public long getAvailableDirectMemory()
    {
        return getAvailableMemory(true);
    }

    @ManagedAttribute("The available bytes retained by heap ByteBuffers")
    public long getAvailableHeapMemory()
    {
        return getAvailableMemory(false);
    }

    private long getAvailableMemory(boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        long total = 0L;
        for (RetainedBucket bucket : buckets)
        {
            int capacity = bucket._capacity;
            total += bucket.values().stream().filter(Pool.Entry::isIdle).count() * capacity;
        }
        return total;
    }

    @ManagedOperation(value = "Clears this RetainableByteBufferPool", impact = "ACTION")
    public void clear()
    {
        clearArray(_direct, _currentDirectMemory);
        clearArray(_indirect, _currentHeapMemory);
    }

    private void clearArray(RetainedBucket[] poolArray, AtomicLong memoryCounter)
    {
        for (RetainedBucket pool : poolArray)
        {
            for (RetainedBucket.Entry entry : pool.values())
            {
                if (entry.remove())
                {
                    memoryCounter.addAndGet(-entry.getPooled().capacity());
                    removed(entry.getPooled());
                }
            }
        }
    }

    private void releaseExcessMemory(boolean direct)
    {
        long maxMemory = direct ? _maxDirectMemory : _maxHeapMemory;
        if (maxMemory > 0)
        {
            long excess = getMemory(direct) - maxMemory;
            if (excess > 0)
                evict(direct, excess);
        }
    }

    /**
     * This eviction mechanism searches for the RetainableByteBuffers that were released the longest time ago.
     * @param direct true to search in the direct buffers buckets, false to search in the heap buffers buckets.
     * @param excess the amount of bytes to evict. At least this much will be removed from the buckets.
     */
    private void evict(boolean direct, long excess)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("evicting {} bytes from {} pools", excess, (direct ? "direct" : "heap"));
        long now = System.nanoTime();
        long totalClearedCapacity = 0L;

        RetainedBucket[] buckets = direct ? _direct : _indirect;

        while (totalClearedCapacity < excess)
        {
            for (RetainedBucket bucket : buckets)
            {
                RetainedBucket.Entry oldestEntry = findOldestEntry(now, bucket);
                if (oldestEntry == null)
                    continue;

                if (oldestEntry.remove())
                {
                    int clearedCapacity = oldestEntry.getPooled().capacity();
                    if (direct)
                        _currentDirectMemory.addAndGet(-clearedCapacity);
                    else
                        _currentHeapMemory.addAndGet(-clearedCapacity);
                    totalClearedCapacity += clearedCapacity;
                    removed(oldestEntry.getPooled());
                }
                // else a concurrent thread evicted the same entry -> do not account for its capacity.
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("eviction done, cleared {} bytes from {} pools", totalClearedCapacity, (direct ? "direct" : "heap"));
    }

    @Override
    public String toString()
    {
        return String.format("%s{min=%d,max=%d,buckets=%d,heap=%d/%d,direct=%d/%d}",
            super.toString(),
            _minCapacity, _maxCapacity,
            _direct.length,
            _currentHeapMemory.get(), _maxHeapMemory,
            _currentDirectMemory.get(), _maxDirectMemory);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(
            out,
            indent,
            this,
            DumpableCollection.fromArray("direct", _direct),
            DumpableCollection.fromArray("indirect", _indirect));
    }

    private Pool<RetainableByteBuffer>.Entry findOldestEntry(long now, Pool<RetainableByteBuffer> bucket)
    {
        RetainedBucket.Entry oldestEntry = null;
        for (RetainedBucket.Entry entry : bucket.values())
        {
            if (oldestEntry != null)
            {
                long entryAge = now - entry.getPooled().getLastUpdate();
                if (entryAge > now - oldestEntry.getPooled().getLastUpdate())
                    oldestEntry = entry;
            }
            else
            {
                oldestEntry = entry;
            }
        }
        return oldestEntry;
    }

    private static class RetainedBucket extends Pool<RetainableByteBuffer>
    {
        private final int _capacity;

        RetainedBucket(int capacity, int size)
        {
            super(Pool.StrategyType.THREAD_ID, size, true);
            _capacity = capacity;
        }

        @Override
        public String toString()
        {
            int entries = 0;
            int inUse = 0;
            for (Entry entry : values())
            {
                entries++;
                if (entry.isInUse())
                    inUse++;
            }

            return String.format("%s{capacity=%d,inuse=%d(%d%%)}",
                super.toString(),
                _capacity,
                inUse,
                entries > 0 ? (inUse * 100) / entries : 0);
        }
    }
}
