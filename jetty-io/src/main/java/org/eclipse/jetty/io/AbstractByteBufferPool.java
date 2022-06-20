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

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

/**
 * The {@code maxHeapMemory} and {@code maxDirectMemory} default heuristic is to use {@link Runtime#maxMemory()}
 * divided by 4.</p>
 */
@ManagedObject
abstract class AbstractByteBufferPool implements ByteBufferPool
{
    private final int _factor;
    private final int _maxCapacity;
    private final int _maxBucketSize;
    private final long _maxHeapMemory;
    private final long _maxDirectMemory;
    private final AtomicLong _heapMemory = new AtomicLong();
    private final AtomicLong _directMemory = new AtomicLong();
    private final RetainableByteBufferPool _retainableByteBufferPool;
    
    /**
     * Creates a new ByteBufferPool with the given configuration.
     *
     * @param factor the capacity factor
     * @param maxBucketSize the maximum ByteBuffer queue length
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param retainedHeapMemory the max heap memory in bytes, -1 for no retained memory or 0 to use default heuristic
     * @param retainedDirectMemory the max direct memory in bytes, -1 for no retained memory or 0 to use default heuristic
     */
    protected AbstractByteBufferPool(int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory, long retainedHeapMemory, long retainedDirectMemory)
    {
        _factor = factor <= 0 ? 1024 : factor;
        _maxCapacity = maxCapacity > 0 ? maxCapacity : 64 * _factor;
        _maxBucketSize = maxBucketSize;
        _maxHeapMemory = (maxHeapMemory != 0) ? maxHeapMemory : Runtime.getRuntime().maxMemory() / 4;
        _maxDirectMemory = (maxDirectMemory != 0) ? maxDirectMemory : Runtime.getRuntime().maxMemory() / 4;

        if (retainedHeapMemory < 0 && retainedDirectMemory < 0)
            _retainableByteBufferPool = RetainableByteBufferPool.from(this);
        else
            _retainableByteBufferPool = newRetainableByteBufferPool(factor, maxCapacity, maxBucketSize,
                (retainedHeapMemory != 0) ? retainedHeapMemory : Runtime.getRuntime().maxMemory() / 4,
                (retainedDirectMemory != 0) ? retainedDirectMemory : Runtime.getRuntime().maxMemory() / 4);
    }

    protected RetainableByteBufferPool newRetainableByteBufferPool(int factor, int maxCapacity, int maxBucketSize, long retainedHeapMemory, long retainedDirectMemory)
    {
        return RetainableByteBufferPool.from(this);
    }

    @Override
    public RetainableByteBufferPool asRetainableByteBufferPool()
    {
        return _retainableByteBufferPool;
    }

    protected int getCapacityFactor()
    {
        return _factor;
    }

    protected int getMaxCapacity()
    {
        return _maxCapacity;
    }

    protected int getMaxBucketSize()
    {
        return _maxBucketSize;
    }

    @Deprecated
    protected void decrementMemory(ByteBuffer buffer)
    {
        updateMemory(buffer, false);
    }

    @Deprecated
    protected void incrementMemory(ByteBuffer buffer)
    {
        updateMemory(buffer, true);
    }

    private void updateMemory(ByteBuffer buffer, boolean addOrSub)
    {
        AtomicLong memory = buffer.isDirect() ? _directMemory : _heapMemory;
        int capacity = buffer.capacity();
        memory.addAndGet(addOrSub ? capacity : -capacity);
    }

    protected void releaseExcessMemory(boolean direct, Consumer<Boolean> clearFn)
    {
        long maxMemory = direct ? _maxDirectMemory : _maxHeapMemory;
        if (maxMemory > 0)
        {
            while (getMemory(direct) > maxMemory)
            {
                clearFn.accept(direct);
            }
        }
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

    @ManagedAttribute("The max num of bytes that can be retained from direct ByteBuffers")
    public long getMaxDirectMemory()
    {
        return _maxDirectMemory;
    }

    @ManagedAttribute("The max num of bytes that can be retained from heap ByteBuffers")
    public long getMaxHeapMemory()
    {
        return _maxHeapMemory;
    }

    public long getMemory(boolean direct)
    {
        AtomicLong memory = direct ? _directMemory : _heapMemory;
        return memory.get();
    }

    protected static class Bucket
    {
        private final Queue<ByteBuffer> _queue = new ConcurrentLinkedQueue<>();
        private final int _capacity;
        private final int _maxSize;
        private final AtomicInteger _size;
        private final AtomicLong _lastUpdate = new AtomicLong(System.nanoTime());
        private final IntConsumer _memoryFunction;

        @Deprecated
        public Bucket(int capacity, int maxSize)
        {
            this(capacity, maxSize, i -> {});
        }

        public Bucket(int capacity, int maxSize, IntConsumer memoryFunction)
        {
            _capacity = capacity;
            _maxSize = maxSize;
            _size = maxSize > 0 ? new AtomicInteger() : null;
            _memoryFunction = Objects.requireNonNull(memoryFunction);
        }

        public ByteBuffer acquire()
        {
            ByteBuffer buffer = _queue.poll();
            if (buffer != null)
            {
                if (_size != null)
                    _size.decrementAndGet();
                _memoryFunction.accept(-buffer.capacity());
            }

            return buffer;
        }

        public void release(ByteBuffer buffer)
        {
            resetUpdateTime();
            BufferUtil.reset(buffer);
            if (_size == null || _size.incrementAndGet() <= _maxSize)
            {
                _queue.offer(buffer);
                _memoryFunction.accept(buffer.capacity());
            }
            else
            {
                _size.decrementAndGet();
            }
        }

        void resetUpdateTime()
        {
            _lastUpdate.lazySet(System.nanoTime());
        }

        public void clear()
        {
            int size = _size == null ? 0 : _size.get() - 1;
            while (size >= 0)
            {
                ByteBuffer buffer = acquire();
                if (buffer == null)
                    break;
                if (_size != null)
                    --size;
            }
        }

        boolean isEmpty()
        {
            return _queue.isEmpty();
        }

        int size()
        {
            return _queue.size();
        }

        long getLastUpdate()
        {
            return _lastUpdate.getOpaque();
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{capacity=%d, size=%d, maxSize=%d}", getClass().getSimpleName(), hashCode(), _capacity, size(), _maxSize);
        }
    }

    IntConsumer updateMemory(boolean direct)
    {
        return (direct) ? _directMemory::addAndGet : _heapMemory::addAndGet;
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        _heapMemory.set(0);
        _directMemory.set(0);
    }
}
