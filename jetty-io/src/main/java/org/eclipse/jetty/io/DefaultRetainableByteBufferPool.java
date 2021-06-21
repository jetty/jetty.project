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
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;

@ManagedObject
public class DefaultRetainableByteBufferPool implements RetainableByteBufferPool
{
    private final Pool<RetainableByteBuffer>[] _direct;
    private final Pool<RetainableByteBuffer>[] _indirect;
    private final int _factor;
    private final int _minCapacity;

    public DefaultRetainableByteBufferPool()
    {
        this(0, 1024, 65536, Integer.MAX_VALUE);
    }

    public DefaultRetainableByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
    {
        _factor = factor <= 0 ? 1024 : factor;
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
        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>[] indirectArray = new Pool[length];
        for (int i = 0; i < directArray.length; i++)
        {
            directArray[i] = new Pool<>(Pool.StrategyType.THREAD_ID, maxBucketSize, true);
            indirectArray[i] = new Pool<>(Pool.StrategyType.THREAD_ID, maxBucketSize, true);
        }
        _direct = directArray;
        _indirect = indirectArray;
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

    private Pool<RetainableByteBuffer> bucketFor(int capacity, boolean direct)
    {
        if (capacity < _minCapacity)
            return null;
        int idx = bucketIndexFor(capacity);
        Pool<RetainableByteBuffer>[] buckets = direct ? _direct : _indirect;
        if (idx >= buckets.length)
            return null;
        return buckets[idx];
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
        Pool<RetainableByteBuffer>[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(Pool::size).sum();
    }

    /**
     * Find a {@link RetainableByteBufferPool} implementation in the given container, or wrap the given
     * {@link ByteBufferPool} with an adapter.
     * @param container the container to search for an existing memory pool.
     * @param byteBufferPool the {@link ByteBufferPool} to wrap if no memory pool was found in the container.
     * @return the {@link RetainableByteBufferPool} found or the wrapped one.
     */
    public static RetainableByteBufferPool findOrAdapt(Container container, ByteBufferPool byteBufferPool)
    {
        RetainableByteBufferPool retainableByteBufferPool = container == null ? null : container.getBean(RetainableByteBufferPool.class);
        if (retainableByteBufferPool == null)
            retainableByteBufferPool = new AdapterMemoryPool(byteBufferPool);
        return retainableByteBufferPool;
    }

    /**
     * An adapter class which exposes a {@link ByteBufferPool} as a
     * {@link RetainableByteBufferPool}.
     */
    private static class AdapterMemoryPool implements RetainableByteBufferPool
    {
        private final ByteBufferPool byteBufferPool;
        private final Consumer<ByteBuffer> releaser;

        public AdapterMemoryPool(ByteBufferPool byteBufferPool)
        {
            this.byteBufferPool = byteBufferPool;
            this.releaser = byteBufferPool::release;
        }

        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            ByteBuffer byteBuffer = byteBufferPool.acquire(size, direct);
            return new RetainableByteBuffer(byteBuffer, releaser);
        }
    }
}
