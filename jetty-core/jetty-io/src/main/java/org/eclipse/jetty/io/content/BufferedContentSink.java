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
import org.eclipse.jetty.util.IteratingNestedCallback;

/**
 * <p>A {@link Content.Sink} backed by another {@link Content.Sink}.
 * Any content written to this {@link Content.Sink} is buffered,
 * then written to the delegate using
 * {@link Content.Sink#write(boolean, ByteBuffer, Callback)}. </p>
 */
public class BufferedContentSink implements Content.Sink
{
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
        IteratingNestedCallback writer = new IteratingNestedCallback(callback)
        {
            private boolean complete;

            @Override
            protected Action process()
            {
                if (complete)
                    return Action.SUCCEEDED;
                boolean write = _aggregator.copyBuffer(current);
                complete = last && !current.hasRemaining();
                if (write || complete)
                {
                    RetainableByteBuffer buffer = _aggregator.takeRetainableByteBuffer();
                    _delegate.write(complete, buffer.getByteBuffer(), Callback.from(this, buffer::release));
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };
        writer.iterate();
    }
}
