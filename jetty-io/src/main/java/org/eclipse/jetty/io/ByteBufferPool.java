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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>A {@link ByteBuffer} pool.</p>
 * <p>Acquired buffers may be {@link #release(ByteBuffer) released} but they do not need to;
 * if they are released, they may be recycled and reused, otherwise they will be garbage
 * collected as usual.</p>
 */
public interface ByteBufferPool
{
    /**
     * <p>Requests a {@link ByteBuffer} of the given size.</p>
     * <p>The returned buffer may have a bigger capacity than the size being
     * requested but it will have the limit set to the given size.</p>
     *
     * @param size the size of the buffer
     * @param direct whether the buffer must be direct or not
     * @return the requested buffer
     * @see #release(ByteBuffer)
     */
    ByteBuffer acquire(int size, boolean direct);

    /**
     * <p>Returns a {@link ByteBuffer}, usually obtained with {@link #acquire(int, boolean)}
     * (but not necessarily), making it available for recycling and reuse.</p>
     *
     * @param buffer the buffer to return
     * @see #acquire(int, boolean)
     */
    void release(ByteBuffer buffer);

    /**
     * <p>Removes a {@link ByteBuffer} that was previously obtained with {@link #acquire(int, boolean)}.</p>
     * <p>The buffer will not be available for further reuse.</p>
     *
     * @param buffer the buffer to remove
     * @see #acquire(int, boolean)
     * @see #release(ByteBuffer)
     */
    default void remove(ByteBuffer buffer)
    {
    }

    /**
     * <p>Creates a new ByteBuffer of the given capacity and the given directness.</p>
     *
     * @param capacity the ByteBuffer capacity
     * @param direct the ByteBuffer directness
     * @return a newly allocated ByteBuffer
     */
    default ByteBuffer newByteBuffer(int capacity, boolean direct)
    {
        return direct ? BufferUtil.allocateDirect(capacity) : BufferUtil.allocate(capacity);
    }

    /**
     * Get this pool as a {@link RetainableByteBufferPool}, which supports reference counting of the
     * buffers and possibly a more efficient lookup mechanism based on the {@link org.eclipse.jetty.util.Pool} class.
     * @return This pool wrapped as a RetainableByteBufferPool.
     */
    default RetainableByteBufferPool asRetainableByteBufferPool()
    {
        return RetainableByteBufferPool.from(this);
    }

    class Lease
    {
        private final ByteBufferPool byteBufferPool;
        private final List<ByteBuffer> buffers;
        private final List<Boolean> recycles;

        public Lease(ByteBufferPool byteBufferPool)
        {
            this.byteBufferPool = byteBufferPool;
            this.buffers = new ArrayList<>();
            this.recycles = new ArrayList<>();
        }

        public ByteBuffer acquire(int capacity, boolean direct)
        {
            ByteBuffer buffer = byteBufferPool.acquire(capacity, direct);
            BufferUtil.clearToFill(buffer);
            return buffer;
        }

        public void append(ByteBuffer buffer, boolean recycle)
        {
            buffers.add(buffer);
            recycles.add(recycle);
        }

        public void insert(int index, ByteBuffer buffer, boolean recycle)
        {
            buffers.add(index, buffer);
            recycles.add(index, recycle);
        }

        public List<ByteBuffer> getByteBuffers()
        {
            return buffers;
        }

        public long getTotalLength()
        {
            long length = 0;
            for (ByteBuffer buffer : buffers)
            {
                length += buffer.remaining();
            }
            return length;
        }

        public int getSize()
        {
            return buffers.size();
        }

        public void recycle()
        {
            for (int i = 0; i < buffers.size(); ++i)
            {
                ByteBuffer buffer = buffers.get(i);
                if (recycles.get(i))
                    release(buffer);
            }
            buffers.clear();
            recycles.clear();
        }

        public void release(ByteBuffer buffer)
        {
            byteBufferPool.release(buffer);
        }
    }

    class Bucket
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
}
