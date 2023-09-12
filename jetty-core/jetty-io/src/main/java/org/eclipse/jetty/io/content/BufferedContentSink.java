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
    private ByteBufferAggregator _aggregator;
    private boolean _firstWrite = true;
    private boolean _lastWritten;
    private int _recursionCounter;
    private CountingCallback _countingCallback;

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
        /*
         * The given callback might call write() again, which could stack overflow.
         * To avoid this, the callback is succeeded only when we are not recursing
         * write(); this means we must keep a recursion counter, give a counting
         * callback with a count of 2 to recursive write() calls and
         * succeed that counting callback a second time when recursion counter
         * reaches 1 so that the original callback is then succeeded.
         */
        _recursionCounter++;
        try
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
                Callback cb;
                if (_recursionCounter == 2)
                {
                    if (_countingCallback != null)
                        throw new AssertionError();
                    cb = _countingCallback = new CountingCallback(callback, 2);
                }
                else
                {
                    cb = callback;
                }
                aggregateAndWrite(last, current, cb);
            }
            else
            {
                // current buffer is greater than the max aggregation size
                directWrite(last, current, callback);
            }
        }
        finally
        {
            if (_recursionCounter == 1)
            {
                while (true)
                {
                    Callback cb = _countingCallback;
                    _countingCallback = null;
                    if (cb == null)
                        break;
                    cb.succeeded();
                }
            }
            _recursionCounter--;
        }
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
