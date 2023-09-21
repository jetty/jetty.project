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

/**
 * An accumulator of {@link Content.Chunk}s used to facilitate minimal copy
 * aggregation of multiple chunks.
 */
public class ChunkAccumulator
{
    private static final ByteBufferPool NON_POOLING = new ByteBufferPool.NonPooling();
    private final List<Chunk> _chunks = new ArrayList<>();
    private int _size;

    public ChunkAccumulator()
    {
    }

    /**
     * Add a {@link Chunk} to the accumulator.
     * @param chunk The {@link Chunk} to accumulate
     * @return true if the {@link Chunk} had content and was added to the accumulator.
     */
    public boolean add(Chunk chunk)
    {
        if (chunk.hasRemaining())
        {
            _size = Math.addExact(_size, chunk.remaining());
            return _chunks.add(chunk);
        }
        return false;
    }

    /**
     * Get the total size of the accumulated {@link Chunk}s.
     * @return The total size in bytes.
     */
    public int size()
    {
        return _size;
    }

    public byte[] take()
    {
        byte[] bytes = new byte[_size];
        int offset = 0;
        for (Chunk chunk : _chunks)
        {
            offset += chunk.get(bytes, offset, chunk.remaining());
            chunk.release();
        }
        assert offset == _size;
        _chunks.clear();
        _size = 0;
        return bytes;
    }

    public RetainableByteBuffer take(ByteBufferPool pool, boolean direct)
    {
        if (_size == 0)
            return RetainableByteBuffer.EMPTY;

        if (_chunks.size() == 1)
        {
            Chunk chunk = _chunks.get(0);
            ByteBuffer byteBuffer = chunk.getByteBuffer();

            if (direct == byteBuffer.isDirect())
            {
                _chunks.clear();
                _size = 0;
                return RetainableByteBuffer.wrap(byteBuffer, chunk);
            }
        }

        RetainableByteBuffer buffer = Objects.requireNonNullElse(pool, NON_POOLING).acquire(_size, direct);
        int offset = 0;
        for (Chunk chunk : _chunks)
        {
            offset += chunk.remaining();
            BufferUtil.append(buffer.getByteBuffer(), chunk.getByteBuffer());
            chunk.release();
        }
        assert offset == _size;
        _chunks.clear();
        _size = 0;
        return buffer;
    }

    public void close()
    {
        _chunks.forEach(Chunk::release);
        _chunks.clear();
        _size = 0;
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
        task.run();
        return task;
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
        task.run();
        return task;
    }

    private abstract static class AccumulatorTask<T> extends CompletableTask<T>
    {
        final Content.Source _source;
        final ChunkAccumulator _accumulator = new ChunkAccumulator();
        final int _maxSize;

        AccumulatorTask(Content.Source source, int maxSize)
        {
            _source = source;
            _maxSize = maxSize;
        }

        @Override
        public void run()
        {
            try
            {
                while (true)
                {
                    Chunk chunk = _source.read();
                    if (chunk == null)
                    {
                        _source.demand(this);
                        return;
                    }

                    if (Chunk.isFailure(chunk))
                    {
                        completeExceptionally(chunk.getFailure());
                        return;
                    }

                    if (chunk.hasRemaining())
                    {
                        if (chunk.canRetain())
                            _accumulator.add(chunk);
                        else
                        {
                            _accumulator.add(Chunk.from(BufferUtil.copy(chunk.getByteBuffer()), chunk.isLast(), () ->
                            {}));
                            chunk.release();
                        }

                        if (_maxSize > 0 && _accumulator._size > _maxSize)
                        {
                            _accumulator.close();
                            IOException ioe = new IOException("too large");
                            _source.fail(ioe);
                            completeExceptionally(ioe);
                            return;
                        }
                    }

                    if (chunk.isLast())
                    {
                        complete(take(_accumulator));
                        return;
                    }
                }
            }
            catch (ArithmeticException e)
            {
                _accumulator.close();
                IOException ioe = new IOException("too large");
                _source.fail(ioe);
                completeExceptionally(ioe);
            }
        }

        protected abstract T take(ChunkAccumulator accumulator);

    }
}
