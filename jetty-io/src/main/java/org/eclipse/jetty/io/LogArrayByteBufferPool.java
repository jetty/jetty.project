//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

/**
 * Extension of the {@link ArrayByteBufferPool} whose bucket sizes increase exponentially instead of linearly.
 * Each bucket will be double the size of the previous bucket, this decreases the amounts of buckets required
 * which can lower total memory usage if buffers are often being acquired of different sizes. However as there are
 * fewer buckets this will also increase the contention on each bucket.
 */
public class LogArrayByteBufferPool extends ArrayByteBufferPool
{
    /**
     * Creates a new ByteBufferPool with a default configuration.
     */
    public LogArrayByteBufferPool()
    {
        this(-1, -1, -1);
    }

    /**
     * Creates a new ByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param maxCapacity the maximum ByteBuffer capacity
     */
    public LogArrayByteBufferPool(int minCapacity, int maxCapacity)
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
    public LogArrayByteBufferPool(int minCapacity, int maxCapacity, int maxQueueLength)
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
    public LogArrayByteBufferPool(int minCapacity, int maxCapacity, int maxQueueLength, long maxHeapMemory, long maxDirectMemory)
    {
        super(minCapacity, 1, maxCapacity, maxQueueLength, maxHeapMemory, maxDirectMemory);
    }

    @Override
    protected int getBucketNumber(int capacity)
    {
        return 32 - Integer.numberOfLeadingZeros(capacity - 1);
    }

    @Override
    protected int getBucketCapacity(int bucket)
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
            if (bucket == null || bucket.isEmpty())
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

            // The same bucket may be concurrently
            // removed, so we need this null guard.
            if (bucket != null)
            {
                // Acquire a buffer but never return it to the pool.
                bucket.acquire();
                bucket.resetUpdateTime();
            }
        }
    }
}
