//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public class ArrayRetainableByteBufferPool implements RetainableByteBufferPool
{
    private static final Logger LOG = LoggerFactory.getLogger(ArrayRetainableByteBufferPool.class);

    private final Bucket[] _direct;
    private final Bucket[] _indirect;
    private final int _factor;
    private final int _minCapacity;
    private final int _maxCapacity;
    private final long _maxHeapMemory;
    private final long _maxDirectMemory;
    private final AtomicLong _currentHeapMemory = new AtomicLong();
    private final AtomicLong _currentDirectMemory = new AtomicLong();
    private final Function<Integer, Integer> _bucketIndexFor;
    private final Function<Integer, Integer> _bucketCapacity;

    public ArrayRetainableByteBufferPool()
    {
        this(0, 1024, 65536, Integer.MAX_VALUE, -1L, -1L);
    }

    public ArrayRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, -1L, -1L);
    }

    public ArrayRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory,
            c -> (c - 1) / factor,
            i -> (i + 1) * factor);
    }

    protected ArrayRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory,
                                            Function<Integer, Integer> bucketIndexFor, Function<Integer, Integer> bucketCapacity)
    {
        _factor = factor <= 0 ? 1024 : factor;
        this._maxHeapMemory = maxHeapMemory;
        this._maxDirectMemory = maxDirectMemory;
        if (minCapacity <= 0)
            minCapacity = 0;
        _minCapacity = minCapacity;
        _maxCapacity = maxCapacity;
        if (maxCapacity <= 0)
            maxCapacity = 64 * 1024;
        if ((maxCapacity % _factor) != 0 || _factor >= maxCapacity)
            throw new IllegalArgumentException("The capacity factor must be a divisor of maxCapacity");

        int length = bucketIndexFor.apply(maxCapacity) + 1;

        @SuppressWarnings("unchecked")
        Bucket[] directArray = new Bucket[length];
        @SuppressWarnings("unchecked")
        Bucket[] indirectArray = new Bucket[length];
        for (int i = 0; i < directArray.length; i++)
        {
            int capacity = bucketCapacity.apply(i);
            directArray[i] = new Bucket(capacity, maxBucketSize);
            indirectArray[i] = new Bucket(capacity, maxBucketSize);
        }
        _direct = directArray;
        _indirect = indirectArray;
        _bucketIndexFor = bucketIndexFor;
        _bucketCapacity = bucketCapacity;
    }

    public int getMinCapacity()
    {
        return _minCapacity;
    }

    public int getMaxCapacity()
    {
        return _maxCapacity;
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        Bucket bucket = bucketFor(size, direct);
        if (bucket == null)
            return newRetainableByteBuffer(size, direct, byteBuffer -> {});
        Pool<RetainableByteBuffer>.Entry entry = bucket.acquire();

        RetainableByteBuffer buffer;
        if (entry == null)
        {
            Pool<RetainableByteBuffer>.Entry reservedEntry = bucket.reserve();
            if (reservedEntry != null)
            {
                buffer = newRetainableByteBuffer(bucket._capacity, direct, byteBuffer ->
                {
                    BufferUtil.clear(byteBuffer);
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
                buffer = newRetainableByteBuffer(size, direct, byteBuffer -> {});
            }
        }
        else
        {
            buffer = entry.getPooled();
            buffer.acquire();
        }
        return buffer;
    }

    private RetainableByteBuffer newRetainableByteBuffer(int capacity, boolean direct, Consumer<ByteBuffer> releaser)
    {
        ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        BufferUtil.clear(buffer);
        RetainableByteBuffer retainableByteBuffer = new RetainableByteBuffer(buffer, releaser);
        retainableByteBuffer.acquire();
        return retainableByteBuffer;
    }

    private Bucket bucketFor(int capacity, boolean direct)
    {
        if (capacity < _minCapacity)
            return null;
        int idx = _bucketIndexFor.apply(capacity);
        Bucket[] buckets = direct ? _direct : _indirect;
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
        Pool<RetainableByteBuffer>[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(Pool::size).sum();
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
        Pool<RetainableByteBuffer>[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(pool -> pool.values().stream().filter(Pool.Entry::isIdle).count()).sum();
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
        Pool<RetainableByteBuffer>[] buckets = direct ? _direct : _indirect;
        long total = 0L;
        for (int i = 0; i < buckets.length; i++)
        {
            Pool<RetainableByteBuffer> bucket = buckets[i];
            long capacity = (i + 1L) * _factor;
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

    private void clearArray(Pool<RetainableByteBuffer>[] poolArray, AtomicLong memoryCounter)
    {
        for (Pool<RetainableByteBuffer> retainableByteBufferPool : poolArray)
        {
            for (Pool<RetainableByteBuffer>.Entry entry : retainableByteBufferPool.values())
            {
                entry.remove();
                memoryCounter.addAndGet(-entry.getPooled().capacity());
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

        Pool<RetainableByteBuffer>[] buckets = direct ? _direct : _indirect;

        while (totalClearedCapacity < excess)
        {
            for (Pool<RetainableByteBuffer> bucket : buckets)
            {
                Pool<RetainableByteBuffer>.Entry oldestEntry = findOldestEntry(now, bucket);
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
                }
                // else a concurrent thread evicted the same entry -> do not account for its capacity.
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("eviction done, cleared {} bytes from {} pools", totalClearedCapacity, (direct ? "direct" : "heap"));
    }

    private Pool<RetainableByteBuffer>.Entry findOldestEntry(long now, Pool<RetainableByteBuffer> bucket)
    {
        Pool<RetainableByteBuffer>.Entry oldestEntry = null;
        for (Pool<RetainableByteBuffer>.Entry entry : bucket.values())
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

    private static class Bucket extends Pool<RetainableByteBuffer>
    {
        private final int _capacity;

        Bucket(int capacity, int size)
        {
            super(Pool.StrategyType.THREAD_ID, size, true);
            _capacity = capacity;
        }
    }

    /**
     * A variant of the {@link ArrayRetainableByteBufferPool} that
     * uses buckets of buffers that increase in size by a power of
     * 2 (eg 1k, 2k, 4k, 8k, etc.).
     */
    public static class LogBuckets extends ArrayRetainableByteBufferPool
    {
        public LogBuckets()
        {
            this(0, 65536, Integer.MAX_VALUE);
        }

        public LogBuckets(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            this(minCapacity, maxCapacity, maxBucketSize, -1L, -1L);
        }

        public LogBuckets(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity,
                TypeUtil.nextPowerOf2(maxCapacity) / 2,
                TypeUtil.nextPowerOf2(maxCapacity),
                maxBucketSize,
                maxHeapMemory,
                maxDirectMemory,
                c -> TypeUtil.log2NextPowerOf2(c),
                i -> 1 << i);
        }
    }
}
