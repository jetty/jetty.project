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
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public class RetainableByteBufferPool implements MemoryPool<RetainableByteBuffer>
{
    private final Pool<RetainableByteBuffer>[] _direct;
    private final Pool<RetainableByteBuffer>[] _indirect;
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

        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>[] directArray = new Pool[length];
        _direct = directArray;
        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>[] indirectArray = new Pool[length];
        _indirect = indirectArray;
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        int capacity = (bucketFor(size) + 1) * _factor;
        Pool<RetainableByteBuffer> bucket = bucketFor(size, direct, this::newBucket);
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
        return new Pool<>(Pool.StrategyType.THREAD_ID, _maxBucketSize);
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
