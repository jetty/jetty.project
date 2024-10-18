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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content.Chunk;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CompletableTask;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * An accumulator of {@link Content.Chunk}s used to facilitate minimal copy
 * aggregation of multiple chunks.
 */
public class ChunkAccumulator
{
    private static final ByteBufferPool NON_POOLING = new ByteBufferPool.NonPooling();
    private final List<Chunk> _chunks = new ArrayList<>();
    private int _length;

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
        {
            _length = Math.addExact(_length, chunk.remaining());
            if (chunk.canRetain())
            {
                chunk.retain();
                return _chunks.add(chunk);
            }
            return _chunks.add(Chunk.from(BufferUtil.copy(chunk.getByteBuffer()), chunk.isLast()));
        }
        else if (Chunk.isFailure(chunk))
        {
            throw new IllegalArgumentException("chunk is failure");
        }
        return false;
    }

    /**
     * Get the total length of the accumulated {@link Chunk}s.
     * @return The total length in bytes.
     */
    public int length()
    {
        return _length;
    }

    public byte[] take()
    {
        if (_length == 0)
            return BufferUtil.EMPTY_BYTES;
        byte[] bytes = new byte[_length];
        int offset = 0;
        for (Chunk chunk : _chunks)
        {
            offset += chunk.get(bytes, offset, chunk.remaining());
            chunk.release();
        }
        assert offset == _length;
        _chunks.clear();
        _length = 0;
        return bytes;
    }

    public RetainableByteBuffer take(ByteBufferPool pool, boolean direct)
    {
        if (_length == 0)
            return RetainableByteBuffer.EMPTY;

        if (_chunks.size() == 1)
        {
            Chunk chunk = _chunks.get(0);
            ByteBuffer byteBuffer = chunk.getByteBuffer();

            if (direct == byteBuffer.isDirect())
            {
                _chunks.clear();
                _length = 0;
                return RetainableByteBuffer.wrap(byteBuffer, chunk);
            }
        }

        RetainableByteBuffer buffer = Objects.requireNonNullElse(pool, NON_POOLING).acquire(_length, direct);
        int offset = 0;
        for (Chunk chunk : _chunks)
        {
            offset += chunk.remaining();
            BufferUtil.append(buffer.getByteBuffer(), chunk.getByteBuffer());
            chunk.release();
        }
        assert offset == _length;
        _chunks.clear();
        _length = 0;
        return buffer;
    }

    public void close()
    {
        _chunks.forEach(Chunk::release);
        _chunks.clear();
        _length = 0;
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

    private abstract static class AccumulatorTask<T> extends CompletableTask<T> implements Invocable
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

                    if (_maxLength > 0 && _accumulator._length > _maxLength)
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

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        /**
         * Implementations must be {@link InvocationType#NON_BLOCKING non-blocking},
         * or {@link #getInvocationType()} must be overridden accordingly.
         */
        protected abstract T take(ChunkAccumulator accumulator);
    }
}
