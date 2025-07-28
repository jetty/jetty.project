//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>A pool for {@link RetainableByteBuffer} instances.</p>
 * <p>{@link RetainableByteBuffer} that are {@link #acquire(int, boolean) acquired}
 * <b>must</b> be released by calling {@link RetainableByteBuffer#release()}
 * otherwise the memory they hold will be leaked.</p>
 *
 * <p><b>API NOTE</b></p>
 * <p>This interface does not have a symmetric {@code release(RetainableByteBuffer)}
 * method, because it will be confusing to use due to the fact that the acquired instance
 * <em>is-a</em> {@link Retainable}.</p>
 * <p>Imagine this (hypothetical) code sequence:</p>
 * <pre>{@code
 * RetainableByteBuffer buffer = pool.acquire(size, direct);
 * buffer.retain();
 * pool.release(buffer);
 * }</pre>
 * <p>The hypothetical call to {@code release(RetainableByteBuffer)} would appear to
 * release the buffer to the pool, but in fact the buffer is retained one more time
 * (and therefore still in use) and not really released to the pool.
 * For this reason there is no {@code release(RetainableByteBuffer)} method.</p>
 * <p>Therefore, in order to track acquire/release counts both the pool and the
 * buffer returned by {@link #acquire(int, boolean)} must be wrapped, see
 * {@link RetainableByteBuffer.Wrapper}</p>
 */
public interface ByteBufferPool
{
    ByteBufferPool NON_POOLING = new NonPooling();
    ByteBufferPool.Sized SIZED_NON_POOLING = new Sized(ByteBufferPool.NON_POOLING);

    /**
     * <p>Acquires a {@link RetainableByteBuffer} from this pool.</p>
     *
     * @param size The size of the buffer. The returned buffer will have at least this capacity.
     * @param direct true if a direct memory buffer is needed, false otherwise.
     * @return a {@link RetainableByteBuffer} with position and limit set to 0.
     */
    RetainableByteBuffer acquire(int size, boolean direct);

    /**
     * {@link RetainableByteBuffer#release() Release} the buffer in a way that will remove it from any pool that it may be in.
     * If the buffer is not in a pool, calling this method is equivalent to calling {@link RetainableByteBuffer#release()}.
     * Calling this method satisfies any contract that requires a call to {@link RetainableByteBuffer#release()}.
     * @return {@code true} if a call to {@link RetainableByteBuffer#release()} would have returned {@code true}.
     * @see RetainableByteBuffer#release()
     * @deprecated This API is experimental and may be removed in future releases
     */
    @Deprecated
    default boolean removeAndRelease(RetainableByteBuffer buffer)
    {
        return buffer != null && buffer.release();
    }

    /**
     * <p>Removes all {@link RetainableByteBuffer#isRetained() non-retained}
     * pooled instances from this pool.</p>
     */
    void clear();

    /**
     * <p>A wrapper for {@link ByteBufferPool} instances.</p>
     */
    class Wrapper implements ByteBufferPool
    {
        private final ByteBufferPool wrapped;

        public Wrapper(ByteBufferPool wrapped)
        {
            this.wrapped = wrapped;
        }

        public ByteBufferPool getWrapped()
        {
            return wrapped;
        }

        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            return getWrapped().acquire(size, direct);
        }

        @Override
        public void clear()
        {
            getWrapped().clear();
        }
    }

    /**
     * A ByteBufferPool with an additional no-args {@link #acquire()} method to obtain a buffer of a
     * preconfigured specific size and type.
     */
    class Sized extends Wrapper
    {
        private final boolean _direct;
        private final int _size;

        /**
         * Create a sized pool for non direct buffers of a default size from a wrapped pool.
         * @param wrapped The actual {@link ByteBufferPool}
         */
        public Sized(ByteBufferPool wrapped)
        {
            this(wrapped, false, -1);
        }

        /**
         * Create a sized pool for a give directness and size from a wrapped pool.
         * @param wrapped The actual {@link ByteBufferPool}
         * @param direct {@code true} for direct buffers.
         * @param size The specified size in bytes of the buffer, any value less than 1 means use a default value.
         */
        public Sized(ByteBufferPool wrapped, boolean direct, int size)
        {
            super(Objects.requireNonNullElse(wrapped, NON_POOLING));
            _direct = direct;
            _size = size > 0 ? size : 4096;
        }

        public boolean isDirect()
        {
            return _direct;
        }

        public int getSize()
        {
            return _size;
        }

        /**
         * @return A {@link RetainableByteBuffer} suitable for the specified preconfigured size and type.
         */
        public RetainableByteBuffer acquire()
        {
            return getWrapped().acquire(_size, _direct);
        }

        /**
         * @return A {@link RetainableByteBuffer} suitable for the specified preconfigured type.
         * @param size The specified size in bytes of the buffer
         */
        public RetainableByteBuffer acquire(int size)
        {
            return getWrapped().acquire(size, _direct);
        }

        /**
         * @return A {@link RetainableByteBuffer} suitable for the specified preconfigured size.
         * @param direct true for a direct byte buffer, false otherwise
         */
        public RetainableByteBuffer acquire(boolean direct)
        {
            return getWrapped().acquire(_size, direct);
        }
    }

    /**
     * <p>A {@link ByteBufferPool} that does not pool its
     * {@link RetainableByteBuffer}s.</p>
     * <p>The returned {@code RetainableByteBuffer}s are reference
     * counted.</p>
     * <p>{@code RetainableByteBuffer}s returned by this class
     * are suitable to be wrapped in other {@link Retainable}
     * implementations that may delegate calls to
     * {@link Retainable#retain()}.</p>
     *
     * @see RetainableByteBuffer#wrap(ByteBuffer)
     */
    class NonPooling implements ByteBufferPool
    {
        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            return new Buffer(BufferUtil.allocate(size, direct));
        }

        @Override
        public void clear()
        {
        }

        private static class Buffer extends AbstractRetainableByteBuffer
        {
            private Buffer(ByteBuffer byteBuffer)
            {
                super(byteBuffer);
                acquire();
            }
        }
    }

    /**
     * <p>Accumulates a sequence of {@link RetainableByteBuffer} that
     * are typically created during the generation of protocol bytes.</p>
     * <p>{@code RetainableByteBuffer}s can be either
     * {@link #append(RetainableByteBuffer) appended} to the sequence,
     * or {@link #insert(int, RetainableByteBuffer) inserted} at a
     * specific position in the sequence, and then
     * {@link #release() released} when they are consumed.</p>
     */
    class Accumulator
    {
        private final List<RetainableByteBuffer> buffers = new ArrayList<>();
        private final List<ByteBuffer> byteBuffers = new ArrayList<>();

        public void append(RetainableByteBuffer buffer)
        {
            buffers.add(buffer);
            byteBuffers.add(buffer.getByteBuffer());
        }

        public void insert(int index, RetainableByteBuffer buffer)
        {
            buffers.add(index, buffer);
            byteBuffers.add(index, buffer.getByteBuffer());
        }

        public List<ByteBuffer> getByteBuffers()
        {
            return byteBuffers;
        }

        public long getTotalLength()
        {
            long length = 0;
            for (ByteBuffer buffer : byteBuffers)
            {
                length += buffer.remaining();
            }
            return length;
        }

        public int getSize()
        {
            return byteBuffers.size();
        }

        public void release()
        {
            buffers.forEach(RetainableByteBuffer::release);
            buffers.clear();
            byteBuffers.clear();
        }
    }
}
