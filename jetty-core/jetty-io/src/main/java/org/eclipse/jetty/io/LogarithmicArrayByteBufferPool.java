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

import org.eclipse.jetty.util.NanoTime;

/**
 * Extension of the {@link ArrayByteBufferPool} whose bucket sizes increase exponentially instead of linearly.
 * Each bucket will be double the size of the previous bucket, this decreases the amounts of buckets required
 * which can lower total memory usage if buffers are often being acquired of different sizes. However as there are
 * fewer buckets this will also increase the contention on each bucket.
 */
public class LogarithmicArrayByteBufferPool extends ArrayByteBufferPool
{
    // TODO test this class and use it!

    /**
     * Creates a new ByteBufferPool with a default configuration.
     */
    public LogarithmicArrayByteBufferPool()
    {
        this(-1, -1, -1);
    }

    /**
     * Creates a new ByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param maxCapacity the maximum ByteBuffer capacity
     */
    public LogarithmicArrayByteBufferPool(int minCapacity, int maxCapacity)
    {
        this(minCapacity, maxCapacity, -1, -1, -1);
    }

    /**
     * Creates a new ByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     */
    public LogarithmicArrayByteBufferPool(int minCapacity, int maxCapacity, int maxQueueLength)
    {
        this(minCapacity, maxCapacity, maxQueueLength, -1, -1);
    }

    /**
     * Creates a new ByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param maxHeapMemory the max heap memory in bytes
     * @param maxDirectMemory the max direct memory in bytes
     */
    public LogarithmicArrayByteBufferPool(int minCapacity, int maxCapacity, int maxQueueLength, long maxHeapMemory, long maxDirectMemory)
    {
        this(minCapacity, maxCapacity, maxQueueLength, maxHeapMemory, maxDirectMemory, maxHeapMemory, maxDirectMemory);
    }

    /**
     * Creates a new ByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     * @param maxHeapMemory the max heap memory in bytes
     * @param maxDirectMemory the max direct memory in bytes
     * @param retainedHeapMemory the max heap memory in bytes, -1 for unlimited retained memory or 0 to use default heuristic
     * @param retainedDirectMemory the max direct memory in bytes, -1 for unlimited retained memory or 0 to use default heuristic
     */
    public LogarithmicArrayByteBufferPool(int minCapacity, int maxCapacity, int maxQueueLength, long maxHeapMemory, long maxDirectMemory, long retainedHeapMemory, long retainedDirectMemory)
    {
        super(minCapacity, -1, maxCapacity, maxQueueLength, maxHeapMemory, maxDirectMemory, retainedHeapMemory, retainedDirectMemory);
    }

    @Override
    protected RetainableByteBufferPool newRetainableByteBufferPool(int factor, int maxCapacity, int maxBucketSize, long retainedHeapMemory, long retainedDirectMemory)
    {
        return new LogarithmicRetainablePool(0, maxCapacity, maxBucketSize, retainedHeapMemory, retainedDirectMemory);
    }

    @Override
    protected int bucketFor(int capacity)
    {
        return 32 - Integer.numberOfLeadingZeros(capacity - 1);
    }

    @Override
    protected int capacityFor(int bucket)
    {
        return 1 << bucket;
    }

    @Override
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
            long lastUpdateNanoTime = bucket.getLastUpdate();
            if (oldest == Long.MAX_VALUE || NanoTime.isBefore(lastUpdateNanoTime, oldest))
            {
                oldest = lastUpdateNanoTime;
                index = i;
            }
        }
        if (index >= 0)
        {
            Bucket bucket = buckets[index];
            // Acquire a buffer but never return it to the pool.
            bucket.acquire();
            bucket.resetUpdateTime();
        }
    }

    /**
     * A variant of the {@link ArrayRetainableByteBufferPool} that
     * uses buckets of buffers that increase in size by a power of
     * 2 (eg 1k, 2k, 4k, 8k, etc.).
     */
    public static class LogarithmicRetainablePool extends ArrayRetainableByteBufferPool
    {
        public LogarithmicRetainablePool()
        {
            this(0, -1, Integer.MAX_VALUE);
        }

        public LogarithmicRetainablePool(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            this(minCapacity, maxCapacity, maxBucketSize, -1L, -1L);
        }

        public LogarithmicRetainablePool(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity,
                -1,
                maxCapacity,
                maxBucketSize,
                maxHeapMemory,
                maxDirectMemory,
                c -> 32 - Integer.numberOfLeadingZeros(c - 1),
                i -> 1 << i);
        }
    }
}
