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

package org.eclipse.jetty.io.internal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.Promise;

public class ContentSourceRetainableByteBuffer implements Runnable
{
    private final Accumulator accumulator;
    private final Content.Source source;
    private final Promise<RetainableByteBuffer> promise;

    public ContentSourceRetainableByteBuffer(Content.Source source, ByteBufferPool pool, boolean direct, int maxSize, Promise<RetainableByteBuffer> promise)
    {
        this.source = source;
        this.accumulator = new Accumulator(pool, direct, maxSize);
        this.promise = promise;
    }

    @Override
    public void run()
    {
        while (true)
        {
            Content.Chunk chunk = source.read();

            if (chunk == null)
            {
                source.demand(this);
                return;
            }

            if (Content.Chunk.isFailure(chunk))
            {
                promise.failed(chunk.getFailure());
                if (!chunk.isLast())
                    source.fail(chunk.getFailure());
                return;
            }

            boolean appended = accumulator.append(chunk);
            chunk.release();

            if (!appended)
            {
                IllegalStateException ise = new IllegalStateException("Max size (" + accumulator.capacity() + ") exceeded");
                promise.failed(ise);
                accumulator.release();
                source.fail(ise);
                return;
            }

            if (chunk.isLast())
            {
                promise.succeeded(accumulator);
                accumulator.release();
                return;
            }
        }
    }

    /**
     * An accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
     * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
     */
    private static class Accumulator implements RetainableByteBuffer
    {
        // TODO This ultimately should be a new public Accumulator replacing other Accumulators,
        //      however, it is kept private for now until the common Mutable RBB API is decided.
        private final ReferenceCounter _retainable = new ReferenceCounter();
        private final ByteBufferPool _pool;
        private final boolean _direct;
        private final long _maxLength;
        private final List<RetainableByteBuffer> _buffers = new ArrayList<>();

        /**
         * Construct an accumulating {@link RetainableByteBuffer} that may internally accumulate multiple other
         * {@link RetainableByteBuffer}s with zero-copy if the {@link #append(RetainableByteBuffer)} API is used
         * @param pool The pool from which to allocate buffers
         * @param direct true if direct buffers should be used
         * @param maxLength The maximum length of the accumulated buffers or -1 for 2GB limit
         */
        public Accumulator(ByteBufferPool pool, boolean direct, long maxLength)
        {
            _pool = pool == null ? ByteBufferPool.NON_POOLING : pool;
            _direct = direct;
            _maxLength = maxLength < 0 ? Long.MAX_VALUE : maxLength;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return switch (_buffers.size())
            {
                case 0 -> RetainableByteBuffer.EMPTY.getByteBuffer();
                case 1 -> _buffers.get(0).getByteBuffer();
                default ->
                {
                    RetainableByteBuffer combined = copy(true);
                    _buffers.add(combined);
                    yield combined.getByteBuffer();
                }
            };
        }

        @Override
        public RetainableByteBuffer copy()
        {
            return copy(false);
        }

        private RetainableByteBuffer copy(boolean take)
        {
            int length = remaining();
            RetainableByteBuffer combinedBuffer = _pool.acquire(length, _direct);
            ByteBuffer byteBuffer = combinedBuffer.getByteBuffer();
            BufferUtil.flipToFill(byteBuffer);
            for (RetainableByteBuffer buffer : _buffers)
            {
                byteBuffer.put(buffer.getByteBuffer().slice());
                if (take)
                    buffer.release();
            }
            BufferUtil.flipToFlush(byteBuffer, 0);
            if (take)
                _buffers.clear();
            return combinedBuffer;
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the length of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}
         */
        @Override
        public int remaining()
        {
            long remainingLong = remainingLong();
            return remainingLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(remainingLong);
        }

        public long remainingLong()
        {
            long length = 0;
            for (RetainableByteBuffer buffer : _buffers)
                length += buffer.remaining();
            return length;
        }

        /**
         * {@inheritDoc}
         * @return {@link Integer#MAX_VALUE} if the maxLength of this {@code Accumulator} is greater than {@link Integer#MAX_VALUE}.
         */
        @Override
        public int capacity()
        {
            long capacityLong = capacityLong();
            return capacityLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(capacityLong);
        }

        public long capacityLong()
        {
            return _maxLength;
        }

        @Override
        public boolean canRetain()
        {
            return _retainable.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return _retainable.isRetained();
        }

        @Override
        public void retain()
        {
            _retainable.retain();
        }

        @Override
        public boolean release()
        {
            if (_retainable.release())
            {
                clear();
                return true;
            }
            return false;
        }

        @Override
        public void clear()
        {
            for (RetainableByteBuffer buffer : _buffers)
                buffer.release();
            _buffers.clear();
        }

        public boolean append(ByteBuffer bytes)
        {
            long length = bytes.remaining();
            if (length == 0)
                return true;

            ByteBuffer slice = bytes.slice();
            long space = _maxLength - remainingLong();
            if (space >= length)
            {
                _buffers.add(RetainableByteBuffer.wrap(slice));
                bytes.position(bytes.limit());
                return true;
            }

            length = space;
            slice.limit((int)(slice.position() + length));
            _buffers.add(RetainableByteBuffer.wrap(slice));
            bytes.position((int)(bytes.position() + length));
            return false;
        }

        public boolean append(RetainableByteBuffer retainableBytes)
        {
            long length = retainableBytes.remaining();
            if (length == 0)
                return true;

            long space = _maxLength - remainingLong();
            if (space >= length)
            {
                _buffers.add(retainableBytes.slice());
                retainableBytes.skip(length);
                return true;
            }

            length = space;
            _buffers.add(retainableBytes.slice(length));
            retainableBytes.skip(length);
            return false;
        }

        @Override
        public void putTo(ByteBuffer toInfillMode)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                buffer.putTo(toInfillMode);
                buffer.release();
                i.remove();
            }
        }

        @Override
        public boolean appendTo(ByteBuffer to)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (!buffer.appendTo(to))
                    return false;
                buffer.release();
                i.remove();
            }
            return true;
        }

        @Override
        public boolean appendTo(RetainableByteBuffer to)
        {
            for (Iterator<RetainableByteBuffer> i = _buffers.listIterator(); i.hasNext();)
            {
                RetainableByteBuffer buffer = i.next();
                if (!buffer.appendTo(to))
                    return false;
                buffer.release();
                i.remove();
            }
            return true;
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            switch (_buffers.size())
            {
                case 0 -> callback.succeeded();
                case 1 ->
                {
                    RetainableByteBuffer buffer = _buffers.get(0);
                    buffer.writeTo(sink, last, Callback.from(() ->
                    {
                        if (!buffer.hasRemaining())
                        {
                            buffer.release();
                            _buffers.clear();
                        }
                    }, callback));
                }
                default -> new IteratingNestedCallback(callback)
                {
                    boolean _lastWritten;

                    @Override
                    protected Action process()
                    {
                        while (true)
                        {
                            if (_buffers.isEmpty())
                            {
                                if (last && !_lastWritten)
                                {
                                    _lastWritten = true;
                                    sink.write(true, BufferUtil.EMPTY_BUFFER, this);
                                    return Action.SCHEDULED;
                                }
                                return Action.SUCCEEDED;
                            }

                            RetainableByteBuffer buffer = _buffers.get(0);
                            if (buffer.hasRemaining())
                            {
                                _lastWritten = last && _buffers.size() == 1;
                                buffer.writeTo(sink, _lastWritten, this);
                                return Action.SCHEDULED;
                            }

                            buffer.release();
                            _buffers.remove(0);
                        }
                    }
                }.iterate();
            }
        }
    }
}
