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

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public class RetainableByteBufferPool
{
    private static final Logger LOG = LoggerFactory.getLogger(RetainableByteBufferPool.class);

    private final Pool<RetainableByteBuffer>[] _direct;
    private final Pool<RetainableByteBuffer>[] _indirect;
    private final int _factor;
    private final int _maxQueueLength;
    private final int _minCapacity;

    public RetainableByteBufferPool()
    {
        this(1024, 1024, 65536, Integer.MAX_VALUE);
    }

    public RetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxQueueLength)
    {
        _factor = factor <= 0 ? 1024 : factor;
        _maxQueueLength = maxQueueLength;
        if (minCapacity <= 0)
            minCapacity = 0;
        _minCapacity = minCapacity;
        if (maxCapacity <= 0)
            maxCapacity = 64 * 1024;
        if ((maxCapacity % _factor) != 0 || _factor >= maxCapacity)
            throw new IllegalArgumentException("The capacity factor must be a divisor of maxCapacity");

        int length = maxCapacity / _factor;

        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>[] directArray = new Pool[length];
        _direct = directArray;
        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>[] indirectArray = new Pool[length];
        _indirect = indirectArray;
    }

    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        int capacity = (bucketFor(size) + 1) * _factor;
        Pool<RetainableByteBuffer> bucket = bucketFor(capacity, direct, null);
        if (bucket == null)
            return newRetainableByteBuffer(capacity, direct);
        Pool<RetainableByteBuffer>.Entry entry = bucket.acquire();
        if (entry == null)
            return newRetainableByteBuffer(capacity, direct);
        RetainableByteBuffer buffer = entry.getPooled();
        buffer.retain();
        return buffer;
    }

    public void release(RetainableByteBuffer buffer)
    {
        if (buffer == null)
            return;

        int capacity = buffer.capacity();
        // Validate that this buffer is from this pool.
        if ((capacity % _factor) != 0)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("RetainableByteBuffer {} does not belong to this pool, discarding it", buffer);
            return;
        }

        Object attachment = buffer.getAttachment();
        if (attachment != null && !(attachment instanceof Pool.Entry))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("RetainableByteBuffer {} does not belong to this pool, discarding it", buffer);
            return;
        }

        buffer.release();

        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>.Entry entry = (Pool<RetainableByteBuffer>.Entry)attachment;
        if (entry != null)
        {
            entry.release();
        }
        else
        {
            Pool<RetainableByteBuffer> bucket = bucketFor(capacity, buffer.isDirect(), this::newBucket);
            if (bucket != null)
            {
                Pool<RetainableByteBuffer>.Entry reservedEntry = bucket.reserve();
                if (reservedEntry != null)
                {
                    buffer.setAttachment(reservedEntry);
                    reservedEntry.enable(buffer, false);
                }
            }
        }
    }

    private Pool<RetainableByteBuffer> bucketFor(int capacity, boolean direct, Supplier<Pool<RetainableByteBuffer>> newBucket)
    {
        if (capacity < _minCapacity)
            return null;
        int b = bucketFor(capacity);
        if (b >= _direct.length)
            return null;
        Pool<RetainableByteBuffer>[] buckets = bucketsFor(direct);
        Pool<RetainableByteBuffer> bucket = buckets[b];
        if (bucket == null && newBucket != null)
            buckets[b] = bucket = newBucket.get();
        return bucket;
    }

    private int bucketFor(int capacity)
    {
        return (capacity - 1) / _factor;
    }

    private Pool<RetainableByteBuffer>[] bucketsFor(boolean direct)
    {
        return direct ? _direct : _indirect;
    }

    private Pool<RetainableByteBuffer> newBucket()
    {
        return new Pool<>(Pool.StrategyType.THREAD_ID, _maxQueueLength);
    }

    private RetainableByteBuffer newRetainableByteBuffer(int capacity, boolean direct)
    {
        RetainableByteBuffer retainableByteBuffer = new RetainableByteBuffer(null, capacity, direct);
        retainableByteBuffer.retain();
        return retainableByteBuffer;
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
            .mapToLong(Pool::size)
            .sum();
    }
}
