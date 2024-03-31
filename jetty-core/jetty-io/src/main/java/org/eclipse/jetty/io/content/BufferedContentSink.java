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
    /**
     * An empty {@link ByteBuffer}, which if {@link #write(boolean, ByteBuffer, Callback) written}
     * will invoke a {@link #flush(Callback)} operation.
     */
    public static final ByteBuffer FLUSH_BUFFER = ByteBuffer.wrap(new byte[0]);

    private static final Logger LOG = LoggerFactory.getLogger(BufferedContentSink.class);

    private final Flusher _flusher = new Flusher();
    private final Content.Sink _delegate;
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final int _maxBufferSize;
    private final int _maxAggregationSize;
    private RetainableByteBuffer _aggregator;
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
    
    private void releaseAggregator()
    {
        if (_aggregator != null)
            _aggregator.release();
        _aggregator = null;
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
                _aggregator = new RetainableByteBuffer.Aggregator(_bufferPool, _direct, _maxBufferSize);
            aggregate(last, current, callback);
            return;
        }

        // current buffer is greater than the max aggregation size
        _flusher.flush(last, current, callback);
    }

    /**
     * Flush the buffered content.
     * @param callback Callback completed when the flush is complete
     */
    public void flush(Callback callback)
    {
        _flusher.flush(false, FLUSH_BUFFER, callback);
    }

    /**
     * Aggregates the given buffer, flushing the aggregated buffer if necessary.
     */
    private void aggregate(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        BufferUtil.append(_aggregator.getByteBuffer(), byteBuffer);
        boolean full = byteBuffer.hasRemaining() || _aggregator.isFull();
        boolean empty = !byteBuffer.hasRemaining();
        boolean flush = full || byteBuffer == FLUSH_BUFFER;
        boolean complete = last && empty;
        if (LOG.isDebugEnabled())
            LOG.debug("aggregated current buffer, full={}, complete={}, bytes left={}, aggregator={}", full, complete, byteBuffer.remaining(), _aggregator);
        if (complete || flush)
            _flusher.flush(last, byteBuffer, callback);
        else
            _flusher.serialize(callback);
    }

    private class Flusher extends IteratingCallback
    {
        private enum Scheduled
        {
            FLUSHING_AGGREGATION,
            FLUSHING_BUFFER,
        }

        private Scheduled _scheduled;
        private boolean _flush;
        private boolean _last;
        private ByteBuffer _byteBuffer;
        private Callback _callback;
        private boolean _lastWritten;

        private void flush(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (_callback != null)
                throw new WritePendingException();
            _flush = true;
            _last = last;
            _byteBuffer = byteBuffer;
            _callback = callback;
            iterate();
        }

        private void serialize(Callback callback)
        {
            if (_callback != null)
                throw new WritePendingException();
            _flush = false;
            _callback = callback;
            iterate();
        }

        @Override
        protected Action process()
        {
            if (_scheduled != null)
            {
                switch (_scheduled)
                {
                    case FLUSHING_AGGREGATION ->
                    {
                        _aggregator.clear();
                        if (_byteBuffer != null && _byteBuffer.remaining() <= _maxAggregationSize && !_last)
                        {
                            BufferUtil.append(_aggregator.getByteBuffer(), _byteBuffer);
                            _byteBuffer = null;
                            _flush = false;
                        }
                    }

                    case FLUSHING_BUFFER ->
                        _byteBuffer = null;
                }
                _scheduled = null;
            }

            if (_flush && _aggregator != null && _aggregator.hasRemaining())
            {
                boolean last = _last && BufferUtil.isEmpty(_byteBuffer);
                _lastWritten |= last;
                _aggregator.writeTo(_delegate, last, this);
                _scheduled = Scheduled.FLUSHING_AGGREGATION;
                return Action.SCHEDULED;
            }

            if (_flush && (BufferUtil.hasContent(_byteBuffer) || _byteBuffer == FLUSH_BUFFER))
            {
                ByteBuffer buffer = _byteBuffer;
                _byteBuffer = null;
                _lastWritten |= _last;
                _delegate.write(_last, buffer, this);
                _scheduled = Scheduled.FLUSHING_BUFFER;
                return Action.SCHEDULED;
            }

            if (_last && !_lastWritten)
            {
                _lastWritten = true;
                _delegate.write(_last, BufferUtil.EMPTY_BUFFER, this);
                return Action.SCHEDULED;
            }

            Callback callback = _callback;
            if (callback != null)
            {
                _callback = null;
                callback.succeeded();
                this.succeeded();
                return Action.SCHEDULED;
            }

            return _last ? Action.SUCCEEDED : Action.IDLE;
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            releaseAggregator();
            Callback callback = _callback;
            _callback = null;
            if (callback != null)
                callback.failed(cause);
        }

        @Override
        protected void onCompleteSuccess()
        {
            releaseAggregator();
        }
    }
}
