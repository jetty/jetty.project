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

import org.eclipse.jetty.io.ByteBufferAggregator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
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
    private final Flusher _flusher;
    private ByteBufferAggregator _aggregator;
    private boolean _firstWrite = true;
    private boolean _lastWritten;

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
        _flusher = new Flusher(delegate);
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
        if (current.remaining() <= _maxAggregationSize)
        {
            // current buffer can be aggregated
            if (_aggregator == null)
                _aggregator = new ByteBufferAggregator(_bufferPool, _direct, Math.min(START_BUFFER_SIZE, _maxBufferSize), _maxBufferSize);
            aggregateAndFlush(last, current, callback);
        }
        else
        {
            // current buffer is greater than the max aggregation size
            flush(last, current, callback);
        }
    }

    /**
     * Flushes the aggregated buffer if something was aggregated, then flushes the
     * given buffer, bypassing the aggregator.
     */
    private void flush(boolean last, ByteBuffer currentBuffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("given buffer is greater than _maxBufferSize");

        RetainableByteBuffer aggregatedBuffer = _aggregator == null ? null : _aggregator.takeRetainableByteBuffer();
        if (aggregatedBuffer == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("nothing aggregated, flushing current buffer {}", currentBuffer);
            _flusher.offer(last, currentBuffer, callback);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregated buffer {}", aggregatedBuffer);
            _flusher.offer(false, aggregatedBuffer.getByteBuffer(), new Callback.Nested(Callback.from(aggregatedBuffer::release))
            {
                @Override
                public void succeeded()
                {
                    super.succeeded();
                    if (LOG.isDebugEnabled())
                        LOG.debug("succeeded writing aggregated buffer, flushing current buffer {}", currentBuffer);
                    _flusher.offer(last, currentBuffer, callback);
                }

                @Override
                public void failed(Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failure writing aggregated buffer", x);
                    super.failed(x);
                    callback.failed(x);
                }
            });
        }
    }

    /**
     * Aggregates the given buffer, flushing the aggregated buffer if necessary.
     */
    private void aggregateAndFlush(boolean last, ByteBuffer currentBuffer, Callback callback)
    {
        boolean full = _aggregator.aggregate(currentBuffer);
        boolean complete = last && !currentBuffer.hasRemaining();
        if (LOG.isDebugEnabled())
            LOG.debug("aggregated current buffer, full={}, complete={}, bytes left={}, aggregator={}", full, complete, currentBuffer.remaining(), _aggregator);
        if (complete)
        {
            RetainableByteBuffer aggregatedBuffer = _aggregator.takeRetainableByteBuffer();
            if (aggregatedBuffer != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("complete; writing aggregated buffer as the last one: {} bytes", aggregatedBuffer.remaining());
                _flusher.offer(true, aggregatedBuffer.getByteBuffer(), Callback.from(callback, aggregatedBuffer::release));
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("complete; no aggregated buffer, writing last empty buffer");
                _flusher.offer(true, BufferUtil.EMPTY_BUFFER, callback);
            }
        }
        else if (full)
        {
            RetainableByteBuffer aggregatedBuffer = _aggregator.takeRetainableByteBuffer();
            if (LOG.isDebugEnabled())
                LOG.debug("writing aggregated buffer: {} bytes", aggregatedBuffer.remaining());
            _flusher.offer(false, aggregatedBuffer.getByteBuffer(), new Callback.Nested(Callback.from(aggregatedBuffer::release))
            {
                @Override
                public void succeeded()
                {
                    super.succeeded();
                    if (LOG.isDebugEnabled())
                        LOG.debug("written aggregated buffer, writing remaining of current: {} bytes{}", currentBuffer.remaining(), (last ? " (last write)" : ""));
                    if (last)
                        _flusher.offer(true, currentBuffer, callback);
                    else
                        aggregateAndFlush(false, currentBuffer, callback);
                }

                @Override
                public void failed(Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failure writing aggregated buffer", x);
                    super.failed(x);
                    callback.failed(x);
                }
            });
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("buffer fully aggregated, delaying writing - aggregator: {}", _aggregator);
            _flusher.offer(callback);
        }
    }

    private static class Flusher extends IteratingCallback
    {
        private static final ByteBuffer COMPLETE_CALLBACK = BufferUtil.allocate(0);

        private final Content.Sink _sink;
        private boolean _last;
        private ByteBuffer _buffer;
        private Callback _callback;
        private boolean _lastWritten;

        Flusher(Content.Sink sink)
        {
            _sink = sink;
        }

        void offer(Callback callback)
        {
            offer(false, COMPLETE_CALLBACK, callback);
        }

        void offer(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (_callback != null)
                throw new WritePendingException();
            _last = last;
            _buffer = byteBuffer;
            _callback = callback;
            iterate();
        }

        @Override
        protected Action process()
        {
            if (_lastWritten)
                return Action.SUCCEEDED;
            if (_callback == null)
                return Action.IDLE;
            if (_buffer != COMPLETE_CALLBACK)
            {
                _lastWritten = _last;
                _sink.write(_last, _buffer, this);
            }
            else
            {
                succeeded();
            }
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded()
        {
            _buffer = null;
            Callback callback = _callback;
            _callback = null;
            callback.succeeded();
            super.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            _buffer = null;
            _callback.failed(cause);
        }
    }
}
