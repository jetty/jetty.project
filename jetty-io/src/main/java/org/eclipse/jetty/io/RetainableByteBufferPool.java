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
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class RetainableByteBufferPool implements MemoryPool<RetainableByteBuffer>
{
    private final AtomicReferenceArray<Pool<RetainableByteBuffer>> _direct;
    private final AtomicReferenceArray<Pool<RetainableByteBuffer>> _indirect;
    private final int _factor;
    private final int _maxBucketSize;
    private final int _minCapacity;

    public RetainableByteBufferPool()
    {
        this(0, 1024, 65536, Integer.MAX_VALUE);
    }

    public RetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
    {
        _factor = factor <= 0 ? 1024 : factor;
        _maxBucketSize = maxBucketSize;
        if (minCapacity <= 0)
            minCapacity = 0;
        _minCapacity = minCapacity;
        if (maxCapacity <= 0)
            maxCapacity = 64 * 1024;
        if ((maxCapacity % _factor) != 0 || _factor >= maxCapacity)
            throw new IllegalArgumentException("The capacity factor must be a divisor of maxCapacity");

        int length = maxCapacity / _factor;

        _direct = new AtomicReferenceArray<>(length);
        _indirect = new AtomicReferenceArray<>(length);
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        int capacity = (bucketIndexFor(size) + 1) * _factor;
        Pool<RetainableByteBuffer> bucket = bucketFor(size, direct);
        if (bucket == null)
            return newRetainableByteBuffer(capacity, direct, byteBuffer -> {});
        Pool<RetainableByteBuffer>.Entry entry = bucket.acquire();

        RetainableByteBuffer buffer;
        if (entry == null)
        {
            Pool<RetainableByteBuffer>.Entry reservedEntry = bucket.reserve();
            if (reservedEntry != null)
            {
                buffer = newRetainableByteBuffer(capacity, direct, byteBuffer ->
                {
                    BufferUtil.clear(byteBuffer);
                    reservedEntry.release();
                });
                reservedEntry.enable(buffer, true);
            }
            else
            {
                buffer = newRetainableByteBuffer(capacity, direct, byteBuffer -> {});
            }
        }
        else
        {
            buffer = entry.getPooled();
            buffer.retain();
        }
        return buffer;
    }

    private RetainableByteBuffer newRetainableByteBuffer(int capacity, boolean direct, Consumer<ByteBuffer> releaser)
    {
        ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        BufferUtil.clear(buffer);
        return new RetainableByteBuffer(buffer, releaser);
    }

    @Override
    public void release(RetainableByteBuffer buffer)
    {
        if (buffer == null)
            return;
        buffer.release();
    }

    private Pool<RetainableByteBuffer> bucketFor(int capacity, boolean direct)
    {
        if (capacity < _minCapacity)
            return null;
        int idx = bucketIndexFor(capacity);
        AtomicReferenceArray<Pool<RetainableByteBuffer>> buckets = direct ? _direct : _indirect;
        if (idx >= buckets.length())
            return null;
        Pool<RetainableByteBuffer> bucket = buckets.get(idx);
        if (bucket == null)
        {
            bucket = new Pool<>(Pool.StrategyType.THREAD_ID, _maxBucketSize, true);
            if (!buckets.compareAndSet(idx, null, bucket))
                bucket = buckets.get(idx);
        }
        return bucket;
    }

    private int bucketIndexFor(int capacity)
    {
        return (capacity - 1) / _factor;
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
        long total = 0L;
        AtomicReferenceArray<Pool<RetainableByteBuffer>> buckets = direct ? _direct : _indirect;
        for (int i = 0; i < buckets.length(); i++)
        {
            Pool<RetainableByteBuffer> bucket = buckets.getOpaque(i);
            if (bucket != null)
                total += bucket.size();
        }
        return total;
    }
}
