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
     * <p>The returned buffer may have a bigger capacity than the size being requested.</p>
     *
     * @param size the size of the buffer
     * @param direct whether the buffer must be direct or not
     * @return a buffer with at least the requested capacity, with position and limit set to 0.
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
     * @return This pool as a RetainableByteBufferPool.  The same instance is always returned by multiple calls to this method.
     */
    RetainableByteBufferPool asRetainableByteBufferPool();

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
}
