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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
    /**
     * An empty {@link ByteBuffer}, which if {@link #write(boolean, ByteBuffer, Callback) written}
     * will invoke a {@link #flush(Callback)} operation.
     */
    public static final ByteBuffer FLUSH_BUFFER = ByteBuffer.wrap(new byte[0]);

    private static final Logger LOG = LoggerFactory.getLogger(BufferedContentSink.class);

    private final Content.Sink _delegate;
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final int _maxBufferSize;
    private final int _maxAggregationSize;
    private RetainableByteBuffer _accumulator;
    private boolean _firstWrite = true;
    private boolean _lastWritten;

    public BufferedContentSink(Content.Sink delegate, ByteBufferPool bufferPool, boolean direct, int maxAggregationSize, int maxBufferSize)
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
        if (current.remaining() <= _maxAggregationSize)
        {
            // current buffer can be aggregated
            if (_accumulator == null)
                _accumulator = RetainableByteBuffer.newAccumulator(_bufferPool, _direct, _maxBufferSize);
            aggregateAndFlush(last, current, callback);
        }
        else
        {
            // current buffer is greater than the max aggregation size
            flush(last, current, callback);
        }
    }

    /**
     * Flush the buffered content.
     * @param callback Callback completed when the flush is complete
     */
    public void flush(Callback callback)
    {
        flush(false, FLUSH_BUFFER, callback);
    }

    /**
     * Flushes the aggregated buffer if something was aggregated, then flushes the
     * given buffer, bypassing the aggregator.
     */
    private void flush(boolean last, ByteBuffer currentBuffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("given buffer is greater than _maxBufferSize");

        if (_accumulator == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("nothing aggregated, flushing current buffer {}", currentBuffer);
            _delegate.write(last, currentBuffer, callback);
        }
        else if (BufferUtil.hasContent(currentBuffer))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregated buffer {}", _accumulator);

            _accumulator.writeTo(_delegate, false, new Callback.Nested(Callback.from(_accumulator::release))
            {
                @Override
                public void succeeded()
                {
                    super.succeeded();
                    if (LOG.isDebugEnabled())
                        LOG.debug("succeeded writing aggregated buffer, flushing current buffer {}", currentBuffer);
                    _delegate.write(last, currentBuffer, callback);
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
            _accumulator.writeTo(_delegate, last, Callback.from(_accumulator::release, callback));
        }
    }

    /**
     * Aggregates the given buffer, flushing the aggregated buffer if necessary.
     */
    private void aggregateAndFlush(boolean last, ByteBuffer currentBuffer, Callback callback)
    {
        _accumulator.append(currentBuffer);
        boolean full = _accumulator.isFull();
        boolean empty = !currentBuffer.hasRemaining();
        boolean flush = full || currentBuffer == FLUSH_BUFFER;
        boolean complete = last && empty;
        if (LOG.isDebugEnabled())
            LOG.debug("aggregated current buffer, full={}, complete={}, bytes left={}, aggregator={}", full, complete, currentBuffer.remaining(), _accumulator);
        if (complete)
        {
            if (_accumulator != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("complete; writing aggregated buffer as the last one: {} bytes", _accumulator.remaining());
                _accumulator.writeTo(_delegate, true, Callback.from(callback, _accumulator::release));
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("complete; no aggregated buffer, writing last empty buffer");
                _delegate.write(true, BufferUtil.EMPTY_BUFFER, callback);
            }
        }
        else if (flush)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("writing aggregated buffer: {} bytes, then {}", _accumulator.remaining(), currentBuffer.remaining());

            if (BufferUtil.hasContent(currentBuffer))
            {
                _accumulator.writeTo(_delegate, false, new Callback.Nested(Callback.from(_accumulator::release))
                {
                    @Override
                    public void succeeded()
                    {
                        super.succeeded();
                        if (LOG.isDebugEnabled())
                            LOG.debug("written aggregated buffer, writing remaining of current: {} bytes{}", currentBuffer.remaining(), (last ? " (last write)" : ""));
                        if (last)
                            _delegate.write(true, currentBuffer, callback);
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
                _accumulator.writeTo(_delegate, last, Callback.from(_accumulator::release, callback));
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("buffer fully aggregated, delaying writing - aggregator: {}", _accumulator);
            callback.succeeded();
        }
    }
}
