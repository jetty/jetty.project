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

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content.Chunk;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CompletableTask;

/**
 * An accumulator of {@link Content.Chunk}s used to facilitate minimal copy
 * aggregation of multiple chunks.
 * @deprecated use {@link Content.Source#asRetainableByteBuffer(Content.Source, ByteBufferPool, boolean, int)} instead.
 */
@Deprecated
public class ChunkAccumulator
{
    private final RetainableByteBuffer.DynamicCapacity _accumulator = new RetainableByteBuffer.DynamicCapacity(null, false, -1);

    public ChunkAccumulator()
    {}

    /**
     * Add a {@link Chunk} to the accumulator.
     * @param chunk The {@link Chunk} to accumulate.  If a reference is kept to the chunk (rather than a copy), it will be retained.
     * @return true if the {@link Chunk} had content and was added to the accumulator.
     * @throws ArithmeticException if more that {@link Integer#MAX_VALUE} bytes are added.
     * @throws IllegalArgumentException if the passed {@link Chunk} is a {@link Chunk#isFailure(Chunk) failure}.
     */
    public boolean add(Chunk chunk)
    {
        if (chunk.hasRemaining())
            return _accumulator.append(chunk);
        if (Chunk.isFailure(chunk))
            throw new IllegalArgumentException("chunk is failure");
        return false;
    }

    /**
     * Get the total length of the accumulated {@link Chunk}s.
     * @return The total length in bytes.
     */
    public int length()
    {
        return _accumulator.remaining();
    }

    public byte[] take()
    {
        RetainableByteBuffer buffer = _accumulator.takeRetainableByteBuffer();
        if (buffer.isEmpty())
            return BufferUtil.EMPTY_BUFFER.array();
        return BufferUtil.toArray(buffer.getByteBuffer());
    }

    public RetainableByteBuffer take(ByteBufferPool pool, boolean direct)
    {
        RetainableByteBuffer buffer = _accumulator.takeRetainableByteBuffer();
        RetainableByteBuffer to = Objects.requireNonNullElse(pool, ByteBufferPool.NON_POOLING).acquire(buffer.remaining(), direct);
        buffer.appendTo(to);
        return buffer;
    }

    public void close()
    {
        _accumulator.clear();
    }

    public CompletableFuture<byte[]> readAll(Content.Source source)
    {
        return readAll(source, -1);
    }

    public CompletableFuture<byte[]> readAll(Content.Source source, int maxSize)
    {
        CompletableTask<byte[]> task = new AccumulatorTask<>(source, maxSize)
        {
            @Override
            protected byte[] take(ChunkAccumulator accumulator)
            {
                return accumulator.take();
            }
        };
        return task.start();
    }

    /**
     * @param source The {@link Content.Source} to read
     * @param pool The {@link ByteBufferPool} to acquire the buffer from, or null for a non {@link Retainable} buffer
     * @param direct True if the buffer should be direct.
     * @param maxSize The maximum size to read, or -1 for no limit
     * @return A {@link CompletableFuture} that will be completed when the complete content is read or
     * failed if the max size is exceeded or there is a read error.
     */
    public CompletableFuture<RetainableByteBuffer> readAll(Content.Source source, ByteBufferPool pool, boolean direct, int maxSize)
    {
        CompletableTask<RetainableByteBuffer> task = new AccumulatorTask<>(source, maxSize)
        {
            @Override
            protected RetainableByteBuffer take(ChunkAccumulator accumulator)
            {
                return accumulator.take(pool, direct);
            }
        };
        return task.start();
    }

    private abstract static class AccumulatorTask<T> extends CompletableTask<T>
    {
        private final Content.Source _source;
        private final ChunkAccumulator _accumulator = new ChunkAccumulator();
        private final int _maxLength;

        private AccumulatorTask(Content.Source source, int maxLength)
        {
            _source = source;
            _maxLength = maxLength;
        }

        @Override
        public void run()
        {
            while (true)
            {
                Chunk chunk = _source.read();
                if (chunk == null)
                {
                    _source.demand(this);
                    break;
                }

                if (Chunk.isFailure(chunk))
                {
                    completeExceptionally(chunk.getFailure());
                    if (!chunk.isLast())
                        _source.fail(chunk.getFailure());
                    break;
                }

                try
                {
                    _accumulator.add(chunk);

                    if (_maxLength > 0 && _accumulator.length() > _maxLength)
                        throw new IOException("accumulation too large");
                }
                catch (Throwable t)
                {
                    chunk.release();
                    _accumulator.close();
                    _source.fail(t);
                    completeExceptionally(t);
                    break;
                }

                chunk.release();

                if (chunk.isLast())
                {
                    complete(take(_accumulator));
                    break;
                }
            }
        }

        protected abstract T take(ChunkAccumulator accumulator);
    }
}
