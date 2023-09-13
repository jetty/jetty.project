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

package org.eclipse.jetty.io.content;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferAggregator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CountingCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Content.Sink} backed by another {@link Content.Sink}.
 * Any content written to this {@link Content.Sink} is buffered,
 * then written to the delegate using
 * {@link Content.Sink#write(boolean, ByteBuffer, Callback)}. </p>
 */
public class BufferedContentSink implements Content.Sink
{
    private static final Logger LOG = LoggerFactory.getLogger(BufferedContentSink.class);

    private static final int START_BUFFER_SIZE = 1024;

    private final Content.Sink _delegate;
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final int _maxBufferSize;
    private final int _maxAggregationSize;
    private final AtomicReference<AggregateState> _aggregateState = new AtomicReference<>(AggregateState.IDLE);
    private ByteBufferAggregator _aggregator;
    private boolean _firstWrite = true;
    private boolean _lastWritten;
    private volatile CountingCallback _countingCallback;

    private enum AggregateState
    {
        IDLE,
        WRITING,
        ITERATING,
        PENDING
    }

    public BufferedContentSink(Content.Sink delegate, ByteBufferPool bufferPool, boolean direct, int maxBufferSize, int maxAggregationSize)
    {
        if (maxBufferSize <= 0)
            throw new IllegalArgumentException("maxBufferSize must be > 0, was: " + maxBufferSize);
        if (maxAggregationSize <= 0)
            throw new IllegalArgumentException("maxAggregationSize must be > 0, was: " + maxAggregationSize);
        if (maxBufferSize < maxAggregationSize)
            throw new IllegalArgumentException("maxBufferSize (" + maxBufferSize + ") must be >= maxAggregationSize (" + maxAggregationSize + ")");
        _delegate = delegate;
        _bufferPool = (bufferPool == null) ? new ByteBufferPool.NonPooling() : bufferPool;
        _direct = direct;
        _maxBufferSize = maxBufferSize;
        _maxAggregationSize = maxAggregationSize;
    }

    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("writing last={} {}", last, BufferUtil.toDetailString(byteBuffer));

        if (_lastWritten)
        {
            callback.failed(new IOException("complete"));
            return;
        }
        _lastWritten = last;
        if (_firstWrite)
        {
            _firstWrite = false;
            if (last)
            {
                // No need to buffer if this is both the first and the last write.
                _delegate.write(true, byteBuffer, callback);
                return;
            }
        }

        ByteBuffer current = byteBuffer != null ? byteBuffer : BufferUtil.EMPTY_BUFFER;
        if (current.remaining() > _maxAggregationSize)
        {
            // current buffer is greater than the max aggregation size
            directWrite(last, current, callback);
            return;
        }

        // current buffer can be aggregated
        if (_aggregator == null)
            _aggregator = new ByteBufferAggregator(_bufferPool, _direct, Math.min(START_BUFFER_SIZE, _maxBufferSize), _maxBufferSize);

        AggregateState state = _aggregateState.updateAndGet(s -> switch (s)
        {
            case IDLE -> AggregateState.WRITING;
            case WRITING -> throw new WritePendingException();
            case ITERATING -> AggregateState.ITERATING;
            case PENDING -> throw new IllegalStateException();
        });
        Callback cb = null;

        // while the state is iterating, that means another thread will change to idle unless we change to PENDING first
        while (state == AggregateState.ITERATING)
        {
            // If we can change to PENDING...
            if (_aggregateState.compareAndSet(AggregateState.ITERATING, AggregateState.PENDING))
            {
                // then the other thread will spin waiting for our counting callback
                state = AggregateState.PENDING;
                if (_countingCallback != null)
                    throw new AssertionError();
                cb = _countingCallback = new CountingCallback(callback, 2);
                break;
            }

            // If we couldn't change to PENDING, somebody else changed the state.  Maybe we are idle now...
            if (_aggregateState.compareAndSet(AggregateState.IDLE, AggregateState.WRITING))
            {
                // The WRITING thread changed to IDLE and is exiting, so we are now the WRITING thread
                state = AggregateState.WRITING;
                break;
            }

            // Not ITERATING nor IDLE, so some other thread must have got in with PENDING first. This is
            // probably a concurrent write situation, but let's spin waiting on them for now.
            Thread.onSpinWait();
        }

        // We are not ITERATING, so we are either PENDING or WRITING, if we are the later...
        if (state == AggregateState.WRITING)
        {
            // Wrap the callback so that it will enter ITERATING state before calling the callback,
            // (to allows a concurrent call to write), then iterate after calling the callback.
            cb = new Callback.Nested(callback)
            {
                @Override
                public void succeeded()
                {
                    if (!_aggregateState.compareAndSet(AggregateState.WRITING, AggregateState.ITERATING))
                        throw new IllegalStateException();
                    super.succeeded();
                    iterate();
                }

                @Override
                public void failed(Throwable x)
                {
                    if (!_aggregateState.compareAndSet(AggregateState.WRITING, AggregateState.ITERATING))
                        throw new IllegalStateException();
                    super.failed(x);
                    iterate();
                }

                private void iterate()
                {
                    // we are ITERATING after completing our write
                    while (true)
                    {
                        // If we can change to IDLE, then we can exit
                        if (_aggregateState.compareAndSet(AggregateState.ITERATING, AggregateState.IDLE))
                            return;

                        // We couldn't change to IDLE, so some other thread must have changed us to PENDING
                        assert _aggregateState.get() == AggregateState.PENDING;

                        // Spin waiting for the counting callback
                        while (_countingCallback == null)
                            Thread.onSpinWait();

                        // Take the counting callback
                        Callback countingCallback = _countingCallback;
                        _countingCallback = null;

                        // If we can't switch back to ITERATING, then something has gone wrong
                        if (!_aggregateState.compareAndSet(AggregateState.PENDING, AggregateState.ITERATING))
                            throw new IllegalArgumentException();

                        // succeed the counting callback
                        countingCallback.succeeded();
                    }
                }
            };
        }

        aggregateAndWrite(last, current, cb);
    }

    /**
     * Flushes the aggregated buffer if something was aggregated, then write the
     * given buffer directly, bypassing the aggregator.
     */
    private void directWrite(boolean last, ByteBuffer currentBuffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("given buffer is greater than _maxBufferSize");

        RetainableByteBuffer aggregatedBuffer = _aggregator == null ? null : _aggregator.takeRetainableByteBuffer();
        if (aggregatedBuffer == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("nothing aggregated, flushing current buffer {}", currentBuffer);
            _delegate.write(last, currentBuffer, callback);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregated buffer {}", aggregatedBuffer);
            _delegate.write(false, aggregatedBuffer.getByteBuffer(), new WriteCallback(aggregatedBuffer, callback, () ->
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("flushing current buffer {}", currentBuffer);
                _delegate.write(last, currentBuffer, callback);
            }));
        }
    }

    /**
     * Copies the given buffer to the aggregator, eventually
     */
    private void aggregateAndWrite(boolean last, ByteBuffer currentBuffer, Callback callback)
    {
        boolean write = _aggregator.aggregate(currentBuffer);
        boolean complete = last && !currentBuffer.hasRemaining();
        if (LOG.isDebugEnabled())
            LOG.debug("aggregated current buffer, write={}, complete={}, bytes left={}, aggregator={}", write, complete, currentBuffer.remaining(), _aggregator);
        if (complete)
        {
            RetainableByteBuffer aggregatedBuffer = _aggregator.takeRetainableByteBuffer();
            if (LOG.isDebugEnabled())
                LOG.debug("complete; writing aggregated buffer: {} bytes", aggregatedBuffer.remaining());
            _delegate.write(true, aggregatedBuffer.getByteBuffer(), Callback.from(callback, aggregatedBuffer::release));
        }
        else if (write)
        {
            RetainableByteBuffer aggregatedBuffer = _aggregator.takeRetainableByteBuffer();
            if (LOG.isDebugEnabled())
                LOG.debug("writing aggregated buffer: {} bytes", aggregatedBuffer.remaining());
            _delegate.write(false, aggregatedBuffer.getByteBuffer(), new WriteCallback(aggregatedBuffer, callback, () ->
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("written aggregated buffer, writing remaining of current: {} bytes{}", currentBuffer.remaining(), (last ? " (last write)" : ""));
                if (last)
                    _delegate.write(true, currentBuffer, callback);
                else
                    aggregateAndWrite(false, currentBuffer, callback);
            }));
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("buffer fully aggregated, delaying writing - aggregator: {}", _aggregator);
            callback.succeeded();
        }
    }

    private record WriteCallback(RetainableByteBuffer aggregatedBuffer, Callback delegate, Runnable onSuccess) implements Callback
    {
        @Override
        public void succeeded()
        {
            aggregatedBuffer.release();
            onSuccess.run();
        }

        @Override
        public void failed(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed to write buffer", x);
            aggregatedBuffer.release();
            delegate.failed(x);
        }
    }
}
