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
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link Content.Sink} backed by another {@link Content.Sink}.
 * Any content written to this {@link Content.Sink} is buffered,
 * then written to the delegate's
 * {@link Content.Sink#write(boolean, ByteBuffer, Callback)}. </p>
 */
public class BufferedContentSink implements Content.Sink
{
    private final Content.Sink _delegate;
    private final ByteBufferPool _bufferPool;
    private final boolean _direct;
    private final int _maxBufferSize;
    private CountingByteBufferAccumulator _accumulator;
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
            _accumulator = new CountingByteBufferAccumulator(_bufferPool, _direct, _maxBufferSize);
            _firstWrite = false;
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
                boolean write = _accumulator.copyBuffer(current);
                complete = last && !current.hasRemaining();
                if (write || complete)
                {
                    RetainableByteBuffer buffer = _accumulator.takeRetainableByteBuffer();
                    _delegate.write(complete, buffer.getByteBuffer(), Callback.from(this, buffer::release));
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };
        writer.iterate();
    }

    private static class CountingByteBufferAccumulator
    {
        private static final Logger LOG = LoggerFactory.getLogger(CountingByteBufferAccumulator.class);

        private final ByteBufferPool _bufferPool;
        private final boolean _direct;
        private final int _maxSize;
        private RetainableByteBuffer _retainableByteBuffer;
        private int _accumulatedSize;
        private int _currentSize;

        private CountingByteBufferAccumulator(ByteBufferPool bufferPool, boolean direct, int maxSize)
        {
            if (maxSize <= 0)
                throw new IllegalArgumentException("maxSize must be > 0, was: " + maxSize);
            _bufferPool = (bufferPool == null) ? new ByteBufferPool.NonPooling() : bufferPool;
            _direct = direct;
            _maxSize = maxSize;
            _currentSize = Math.min(1024, _maxSize);
        }

        private boolean copyBuffer(ByteBuffer buffer)
        {
            ensureBufferCapacity(buffer.remaining());
            if (_retainableByteBuffer == null)
            {
                _retainableByteBuffer = _bufferPool.acquire(_currentSize, _direct);
                BufferUtil.flipToFill(_retainableByteBuffer.getByteBuffer());
            }
            int prevPos = buffer.position();
            int copySize = Math.min(_currentSize - _accumulatedSize, buffer.remaining());

            _retainableByteBuffer.getByteBuffer().put(_retainableByteBuffer.getByteBuffer().position(), buffer, buffer.position(), copySize);
            _retainableByteBuffer.getByteBuffer().position(_retainableByteBuffer.getByteBuffer().position() + copySize);
            buffer.position(buffer.position() + copySize);
            _accumulatedSize += buffer.position() - prevPos;
            return _accumulatedSize == _maxSize;
        }

        private void ensureBufferCapacity(int remaining)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ensureBufferCapacity remaining: {} _currentSize: {} _accumulatedSize={}", remaining, _currentSize, _accumulatedSize);
            if (_currentSize == _maxSize)
                return;
            int capacityLeft = _currentSize - _accumulatedSize;
            if (remaining <= capacityLeft)
                return;
            int need = remaining - capacityLeft;
            _currentSize = Math.min(_maxSize, ceilToNextPowerOfTwo(_currentSize + need));

            if (_retainableByteBuffer != null)
            {
                BufferUtil.flipToFlush(_retainableByteBuffer.getByteBuffer(), 0);
                RetainableByteBuffer newBuffer = _bufferPool.acquire(_currentSize, _direct);
                BufferUtil.flipToFill(newBuffer.getByteBuffer());
                newBuffer.getByteBuffer().put(_retainableByteBuffer.getByteBuffer());
                _retainableByteBuffer.release();
                _retainableByteBuffer = newBuffer;
            }
        }

        private static int ceilToNextPowerOfTwo(int val)
        {
            int result = 1 << Integer.SIZE - Integer.numberOfLeadingZeros(val - 1);
            return result > 0 ? result : Integer.MAX_VALUE;
        }

        public RetainableByteBuffer takeRetainableByteBuffer()
        {
            BufferUtil.flipToFlush(_retainableByteBuffer.getByteBuffer(), 0);
            RetainableByteBuffer result = _retainableByteBuffer;
            _retainableByteBuffer = null;
            _accumulatedSize = 0;
            return result;
        }
    }
}
