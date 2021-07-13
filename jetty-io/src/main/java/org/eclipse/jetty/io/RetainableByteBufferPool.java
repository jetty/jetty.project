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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.statistic.SampleStatistic;

/**
 * <p>A {@link RetainableByteBuffer} pool.</p>
 * <p>Acquired buffers <b>must</b> be released by calling {@link RetainableByteBuffer#release()} otherwise the memory they hold will
 * be leaked.</p>
 */
public interface RetainableByteBufferPool
{
    /**
     * Acquires a memory buffer from the pool.
     * @param size The size of the buffer. The returned buffer will have at least this capacity.
     * @param direct true if a direct memory buffer is needed, false otherwise.
     * @return a memory buffer.
     */
    RetainableByteBuffer acquire(int size, boolean direct);

    /**
     * Finds a {@link RetainableByteBufferPool} implementation in the given container, or wrap the given
     * {@link ByteBufferPool} with an adapter.
     * @param container the container to search for an existing memory pool.
     * @param byteBufferPool the {@link ByteBufferPool} to wrap if no memory pool was found in the container.
     * @return the {@link RetainableByteBufferPool} found or the wrapped one.
     */
    static RetainableByteBufferPool findOrAdapt(Container container, ByteBufferPool byteBufferPool)
    {
        RetainableByteBufferPool retainableByteBufferPool = container == null ? null : container.getBean(RetainableByteBufferPool.class);
        if (retainableByteBufferPool == null)
        {
            // Wrap the ByteBufferPool instance.
            retainableByteBufferPool = (size, direct) ->
            {
                ByteBuffer byteBuffer = byteBufferPool.acquire(size, direct);
                RetainableByteBuffer retainableByteBuffer = new RetainableByteBuffer(byteBuffer, byteBufferPool::release);
                retainableByteBuffer.acquire();
                return retainableByteBuffer;
            };
        }
        return retainableByteBufferPool;
    }

    /**
     * A Pool wrapper that collects sample statistics on the acquired
     * buffers.
     * @see SampleStatistic
     */
    @ManagedObject
    class SampledPool implements RetainableByteBufferPool, Dumpable
    {
        private final RetainableByteBufferPool _pool;
        private final SampleStatistic _heap = new SampleStatistic();
        private final SampleStatistic _direct = new SampleStatistic();

        public SampledPool(RetainableByteBufferPool pool)
        {
            _pool = pool;
        }

        @ManagedAttribute("Heap acquire samples")
        public SampleStatistic getHeapSample()
        {
            return _heap;
        }

        @ManagedAttribute("Direct acquire samples")
        public SampleStatistic getDirectSample()
        {
            return _direct;
        }

        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            (direct ? _direct : _heap).record(size);
            return _pool.acquire(size, direct);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent, this,
                Dumpable.named("heap", _heap),
                Dumpable.named("direct", _direct),
                _pool);
        }

        @ManagedAttribute("Pool")
        public RetainableByteBufferPool getPool()
        {
            return _pool;
        }
    }
}
