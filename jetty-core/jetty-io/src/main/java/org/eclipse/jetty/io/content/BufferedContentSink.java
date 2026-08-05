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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Content.Sink} backed by another {@link Content.Sink}.
 * Any content written to this {@link Content.Sink} is buffered,
 * then written to the delegate using
 * {@link Content.Sink#write(boolean, RetainableByteBuffer, Callback)}. </p>
 */
public class BufferedContentSink implements Content.Sink
{
    /**
     * An empty {@link RetainableByteBuffer}, which if {@link #write(boolean, RetainableByteBuffer, Callback) written}
     * will invoke a {@link #flush(Callback)} operation.
     */
    public static final RetainableByteBuffer FLUSH_BUFFER = RetainableByteBuffer.wrap(new byte[0]);

    private static final Logger LOG = LoggerFactory.getLogger(BufferedContentSink.class);

    private final Content.Sink _delegate;
    private final List<RetainableByteBuffer> _aggregator;
    private final SerializedInvoker _serializer = new SerializedInvoker(BufferedContentSink.class);
    private final int _maxSize;
    private final int _aggregationSize;
    private boolean _firstWrite = true;
    private boolean _lastWritten;

    public BufferedContentSink(Content.Sink delegate, ByteBufferPool bufferPool, boolean direct, int maxAggregationSize, int maxBufferSize)
    {
        this(delegate, new ByteBufferPool.Sized(bufferPool, direct, maxAggregationSize), maxBufferSize);
    }

    public BufferedContentSink(Content.Sink delegate, ByteBufferPool.Sized sizedPool, int maxBufferSize)
    {
        if (maxBufferSize <= 0)
            throw new IllegalArgumentException("maxBufferSize must be > 0, was: " + maxBufferSize);
        if (sizedPool.getSize() <= 0)
            throw new IllegalArgumentException("pool.size must be > 0, was: " + sizedPool.getSize());
        if (maxBufferSize < sizedPool.getSize())
            throw new IllegalArgumentException("maxBufferSize (" + maxBufferSize + ") must be >= pool.size (" + sizedPool.getSize() + ")");

        _maxSize = maxBufferSize;
        _aggregationSize = sizedPool.getSize();
        _delegate = delegate;
        _aggregator = new ArrayList<>();
    }

    @Override
    public void write(boolean last, RetainableByteBuffer buffer, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("writing last={} {}", last, buffer);

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
                _delegate.write(true, buffer, callback);
                return;
            }
        }

        RetainableByteBuffer current = buffer != null ? buffer : RetainableByteBuffer.empty();
        if (current.remaining() <= _aggregationSize && !last && buffer != FLUSH_BUFFER)
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
    private void flush(boolean last, RetainableByteBuffer currentBuffer, Callback callback)
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
            RetainableByteBuffer accumulated = RetainableByteBuffer.wrap(_aggregator);
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregate {}", accumulated);
            _delegate.write(last, accumulated, callback);
            accumulated.release();
            _aggregator.forEach(RetainableByteBuffer::release);
            _aggregator.clear();
        }
        else if (last && currentBuffer.remaining() <= Math.min(_aggregationSize, aggregatorSpace()) && aggregatorAppend(currentBuffer))
        {
            currentBuffer.retain();
            RetainableByteBuffer accumulated = RetainableByteBuffer.wrap(_aggregator);
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregated {}", accumulated);
            _delegate.write(last, accumulated, callback);
            accumulated.release();
            _aggregator.forEach(RetainableByteBuffer::release);
            _aggregator.clear();
        }
        else
        {
            RetainableByteBuffer accumulated = RetainableByteBuffer.wrap(_aggregator);
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregate {} and buffer {}", accumulated, currentBuffer);
            _delegate.write(false, accumulated, new Callback() 
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
            accumulated.release();
            _aggregator.forEach(RetainableByteBuffer::release);
            _aggregator.clear();
        }
    }

    private boolean aggregatorAppend(RetainableByteBuffer buffer)
    {
        long totalRemaining = 0L;
        for (RetainableByteBuffer readableBuffer : _aggregator)
        {
            totalRemaining += readableBuffer.remaining();
        }

        if (totalRemaining == _maxSize)
            return false;

        if (totalRemaining + buffer.remaining() > _maxSize)
        {
            long sliceLength = _maxSize - totalRemaining;
            RetainableByteBuffer slice = buffer.slice(buffer.readPosition(), sliceLength);
            buffer.readPosition(buffer.readPosition() + sliceLength);
            _aggregator.add(slice);
            return false;
        }

        buffer.retain();
        _aggregator.add(buffer);
        return true;
    }

    private long aggregatorSpace()
    {
        long totalRemaining = 0L;
        for (RetainableByteBuffer readableBuffer : _aggregator)
        {
            totalRemaining += readableBuffer.remaining();
        }
        return _maxSize - totalRemaining;
    }

    /**
     * Aggregates the given buffer, flushing the aggregated buffer if necessary.
     */
    private void aggregateAndFlush(RetainableByteBuffer currentBuffer, Callback callback)
    {
        if (aggregatorAppend(currentBuffer))
        {
            _serializer.run(callback::succeeded);
            return;
        }

        RetainableByteBuffer accumulated = RetainableByteBuffer.wrap(_aggregator);
        _delegate.write(false, accumulated, new Callback()
        {
            @Override
            public void succeeded()
            {
                if (aggregatorAppend(currentBuffer))
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
        accumulated.release();
        _aggregator.forEach(RetainableByteBuffer::release);
        _aggregator.clear();
    }
}
