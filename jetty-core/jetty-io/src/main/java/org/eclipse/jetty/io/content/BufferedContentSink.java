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

    private final Content.Sink _delegate;
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final int _maxBufferSize;
    private ByteBufferAggregator _aggregator;
    private boolean _firstWrite = true;
    private boolean _lastWritten;

    public BufferedContentSink(Content.Sink delegate, ByteBufferPool bufferPool, boolean direct, int maxBufferSize)
    {
        if (maxBufferSize <= 0)
            throw new IllegalArgumentException("maxBufferSize must be > 0, was: " + maxBufferSize);
        _delegate = delegate;
        _bufferPool = (bufferPool == null) ? new ByteBufferPool.NonPooling() : bufferPool;
        _direct = direct;
        _maxBufferSize = maxBufferSize;
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
        if (_firstWrite)
        {
            _firstWrite = false;
            if (last)
            {
                // No need to buffer if this is both the first and the last write.
                _lastWritten = true;
                _delegate.write(true, byteBuffer, callback);
                return;
            }
            _aggregator = new ByteBufferAggregator(_bufferPool, _direct, Math.min(1024, _maxBufferSize), _maxBufferSize);
        }
        _lastWritten |= last;

        ByteBuffer current = byteBuffer != null ? byteBuffer : BufferUtil.EMPTY_BUFFER;

        if (current.remaining() > _maxBufferSize)
        {
            // given buffer is greater than aggregate's max buffer size
            directWrite(last, current, callback);
        }
        else
        {
            // given buffer can be aggregated
            aggregateAndWrite(last, current, callback);
        }
    }

    private void directWrite(boolean last, ByteBuffer current, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("given buffer is greater than _maxBufferSize");

        RetainableByteBuffer buffer = _aggregator.takeRetainableByteBuffer();
        if (buffer == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("nothing aggregated, flushing current buffer {}", current);
            _delegate.write(last, current, callback);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("flushing aggregated buffer {}", buffer);
            _delegate.write(false, buffer.getByteBuffer(), new Callback()
            {
                @Override
                public void succeeded()
                {
                    buffer.release();
                    if (LOG.isDebugEnabled())
                        LOG.debug("flushing current buffer {}", current);
                    _delegate.write(last, current, callback);
                }

                @Override
                public void failed(Throwable x)
                {
                    buffer.release();
                    callback.failed(x);
                }
            });
        }
    }

    private void aggregateAndWrite(boolean last, ByteBuffer current, Callback callback)
    {
        boolean write = _aggregator.copyBuffer(current);
        boolean complete = last && !current.hasRemaining();
        if (LOG.isDebugEnabled())
            LOG.debug("aggregated current buffer, write={}, complete={}, bytes left={}, aggregator={}", write, complete, current.remaining(), _aggregator);
        if (write || complete)
        {
            RetainableByteBuffer buffer = _aggregator.takeRetainableByteBuffer();
            if (LOG.isDebugEnabled())
                LOG.debug("writing aggregated buffer: {} bytes", buffer.remaining());
            _delegate.write(complete, buffer.getByteBuffer(), new Callback() {
                @Override
                public void succeeded()
                {
                    buffer.release();
                    if (complete)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("write complete");
                        callback.succeeded();
                    }
                    else
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("written aggregated buffer, writing remaining of current: {} bytes{}", current.remaining(), (last ? " (last write)" : ""));
                        if (last)
                            _delegate.write(last, current, callback);
                        else
                            aggregateAndWrite(last, current, callback);
                    }
                }

                @Override
                public void failed(Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("failed to write buffer", x);
                    buffer.release();
                    callback.failed(x);
                }
            });
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("buffer fully aggregated, delaying writing - aggregator: {}", _aggregator);
            callback.succeeded();
        }
    }
}
