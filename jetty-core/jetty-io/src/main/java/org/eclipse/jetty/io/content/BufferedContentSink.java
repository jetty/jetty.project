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
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.SerializedInvoker;
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

    private static final int START_BUFFER_SIZE = 1024;

    private final Content.Sink _delegate;
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final int _maxBufferSize;
    private final int _maxAggregationSize;
    private final RetainableByteBuffer.DynamicCapacity _aggregator;
    private final SerializedInvoker _serializer = new SerializedInvoker();
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
        _aggregator = new RetainableByteBuffer.DynamicCapacity(bufferPool, direct, maxBufferSize);
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
        if (current.remaining() <= _maxAggregationSize && !last && byteBuffer != FLUSH_BUFFER)
        {
            // current buffer can be aggregated
            aggregateAndFlush(current, callback);
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

        if (_aggregator.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("nothing aggregated, flushing current buffer {}", currentBuffer);
            _delegate.write(last, currentBuffer, callback);
        }
        else if (!currentBuffer.hasRemaining())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregate {}", _aggregator);
            _aggregator.writeTo(_delegate, last, callback);
        }
        else if (last && currentBuffer.remaining() <= Math.min(_maxAggregationSize, _aggregator.space()) && _aggregator.append(currentBuffer))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregated {}", _aggregator);
            _aggregator.writeTo(_delegate, true, callback);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregate {} and buffer {}", _aggregator, currentBuffer);

            _aggregator.writeTo(_delegate, false, new Callback()
            {
                @Override
                public void succeeded()
                {
                    _delegate.write(last, currentBuffer, callback);
                }

                @Override
                public void failed(Throwable x)
                {
                    callback.failed(x);
                }

                @Override
                public InvocationType getInvocationType()
                {
                    return callback.getInvocationType();
                }
            });
        }
    }

    /**
     * Aggregates the given buffer, flushing the aggregated buffer if necessary.
     */
    private void aggregateAndFlush(ByteBuffer currentBuffer, Callback callback)
    {
        if (_aggregator.append(currentBuffer))
        {
            _serializer.run(callback::succeeded);
            return;
        }

        _aggregator.writeTo(_delegate, false, new Callback()
        {
            @Override
            public void succeeded()
            {
                if (_aggregator.append(currentBuffer))
                    callback.succeeded();
                else
                    callback.failed(new BufferOverflowException());
            }

            @Override
            public void failed(Throwable x)
            {
                callback.failed(x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return callback.getInvocationType();
            }
        });
    }
}
