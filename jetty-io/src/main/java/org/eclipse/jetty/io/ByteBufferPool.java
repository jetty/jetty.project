//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
    public ByteBuffer acquire(int size, boolean direct);

    /**
     * <p>Returns a {@link ByteBuffer}, usually obtained with {@link #acquire(int, boolean)}
     * (but not necessarily), making it available for recycling and reuse.</p>
     *
     * @param buffer the buffer to return
     * @see #acquire(int, boolean)
     */
    public void release(ByteBuffer buffer);

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

    public static class Lease
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

    public static class Bucket
    {
        private final Deque<ByteBuffer> _queue = new ConcurrentLinkedDeque<>();
        private final int _capacity;
        private final int _maxSize;
        private final AtomicInteger _size;
        private long _lastUpdate = System.nanoTime();

        public Bucket(int capacity, int maxSize)
        {
            _capacity = capacity;
            _maxSize = maxSize;
            _size = maxSize > 0 ? new AtomicInteger() : null;
        }

        public ByteBuffer acquire()
        {
            ByteBuffer buffer = queuePoll();
            if (buffer == null)
                return null;
            if (_size != null)
                _size.decrementAndGet();
            return buffer;
        }

        public void release(ByteBuffer buffer)
        {
            _lastUpdate = System.nanoTime();
            BufferUtil.clear(buffer);
            if (_size == null)
                queueOffer(buffer);
            else if (_size.incrementAndGet() <= _maxSize)
                queueOffer(buffer);
            else
                _size.decrementAndGet();
        }

        public void clear()
        {
            clear(null);
        }

        void clear(Consumer<ByteBuffer> memoryFn)
        {
            int size = _size == null ? 0 : _size.get() - 1;
            while (size >= 0)
            {
                ByteBuffer buffer = queuePoll();
                if (buffer == null)
                    break;
                if (memoryFn != null)
                    memoryFn.accept(buffer);
                if (_size != null)
                {
                    _size.decrementAndGet();
                    --size;
                }
            }
        }

        private void queueOffer(ByteBuffer buffer)
        {
            _queue.offerFirst(buffer);
        }

        private ByteBuffer queuePoll()
        {
            return _queue.poll();
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
            return _lastUpdate;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%d/%d@%d}", getClass().getSimpleName(), hashCode(), size(), _maxSize, _capacity);
        }
    }
}
