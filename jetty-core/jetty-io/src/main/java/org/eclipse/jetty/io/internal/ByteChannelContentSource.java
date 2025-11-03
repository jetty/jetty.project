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

package org.eclipse.jetty.io.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * <p>A {@link Content.Source} backed by a  {@link ByteChannel}.
 * Any calls to {@link #demand(Runnable)} are immediately satisfied.</p>
 */
public class ByteChannelContentSource implements Content.Source
{
    private final AutoLock lock = new AutoLock();
    private final SerializedInvoker _invoker = new SerializedInvoker(ByteChannelContentSource.class);
    private final ByteBufferPool.Sized _byteBufferPool;
    private ByteChannel _byteChannel;
    private final long _offset;
    private final long _length;
    private RetainableByteBuffer _buffer;
    private long _offsetRemaining;
    private long _totalRead;
    private Runnable demandCallback;
    private Content.Chunk _terminal;

    /**
     * Create a new instance that reads from a {@link ByteChannel}.
     *
     * @param byteBufferPool The {@link org.eclipse.jetty.io.ByteBufferPool.Sized} to use for any internal buffers.
     * @param byteChannel The {@link ByteChannel}s to use as the source.
     */
    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel)
    {
        this(byteBufferPool, byteChannel, 0L, -1L);
    }

    /**
     * Create a new instance that reads from a {@link ByteChannel}.
     *
     * @param byteBufferPool The {@link org.eclipse.jetty.io.ByteBufferPool.Sized} to use for any internal buffers.
     * @param byteChannel The {@link ByteChannel}s to use as the source.
     * @param offset the offset byte of the content to start from.
     * Must be greater than or equal to 0 and less than the content length (if known).
     * @param length the length of the content to make available, -1 for the full length.
     * If the size of the content is known, the length may be truncated to the content size minus the offset.
     * @throws IndexOutOfBoundsException if the offset or length are out of range.
     * @see TypeUtil#checkOffsetLengthSize(long, long, long)
     */
    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel, long offset, long length)
    {
        _byteBufferPool = Objects.requireNonNullElse(byteBufferPool, ByteBufferPool.SIZED_NON_POOLING);
        _byteChannel = byteChannel;
        _offset = offset;
        _length = TypeUtil.checkOffsetLengthSize(offset, length, -1L);
        _offsetRemaining = offset;
    }

    protected AutoLock lock()
    {
        return lock.lock();
    }

    public ByteBufferPool.Sized getByteBufferPool()
    {
        return _byteBufferPool;
    }

    public ByteChannel getByteChannel()
    {
        try (AutoLock ignored = lock())
        {
            return _byteChannel;
        }
    }

    public long getOffset()
    {
        return _offset;
    }

    @Override
    public long getLength()
    {
        return _length;
    }

    protected ByteChannel open() throws IOException
    {
        return getByteChannel();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        try (AutoLock ignored = lock())
        {
            if (this.demandCallback != null)
                throw new IllegalStateException("demand pending");
            this.demandCallback = demandCallback;
        }
        _invoker.run(this::invokeDemandCallback);
    }

    private void invokeDemandCallback()
    {
        Runnable demandCallback;
        try (AutoLock ignored = lock())
        {
            demandCallback = this.demandCallback;
            this.demandCallback = null;
        }
        if (demandCallback != null)
            ExceptionUtil.run(demandCallback, this::fail);
    }

    protected void lockedSetTerminal(Content.Chunk terminal)
    {
        assert lock.isHeldByCurrentThread();
        if (_terminal == null)
            _terminal = Objects.requireNonNull(terminal);
        else
            ExceptionUtil.addSuppressedIfNotAssociated(_terminal.getFailure(), terminal.getFailure());
        IO.close(_byteChannel);
        if (_buffer != null)
            _buffer.release();
        _buffer = null;
    }

    protected Content.Chunk lockedEnsureOpenOrTerminal()
    {
        assert lock.isHeldByCurrentThread();
        if (_terminal == null && (_byteChannel == null || !_byteChannel.isOpen()))
        {
            try
            {
                _byteChannel = open();
                if (_byteChannel == null || !_byteChannel.isOpen())
                    lockedSetTerminal(Content.Chunk.from(new ClosedChannelException(), true));
            }
            catch (IOException e)
            {
                lockedSetTerminal(Content.Chunk.from(e, true));
            }
        }
        return _terminal;
    }

    @Override
    public Content.Chunk read()
    {
        try (AutoLock ignored = lock())
        {
            Content.Chunk terminal = lockedEnsureOpenOrTerminal();

            if (terminal != null)
                return terminal;

            if (_length == 0)
            {
                lockedSetTerminal(Content.Chunk.EOF);
                return Content.Chunk.EOF;
            }

            if (_buffer == null)
            {
                _buffer = _byteBufferPool.acquire();
            }
            else if (_buffer.isRetained())
            {
                _buffer.release();
                _buffer = _byteBufferPool.acquire();
            }

            try
            {
                Content.Chunk skipped = skipToOffset();
                if (skipped != Content.Chunk.EMPTY)
                    return skipped;

                ByteBuffer byteBuffer = _buffer.getByteBuffer();
                BufferUtil.clearToFill(byteBuffer);
                if (_length > 0)
                    byteBuffer.limit((int)Math.min(_buffer.capacity(), _length - _totalRead));
                int read = _byteChannel.read(byteBuffer);
                BufferUtil.flipToFlush(byteBuffer, 0);
                if (read > 0)
                {
                    _totalRead += read;
                    _buffer.retain();
                    if (_length < 0 || _totalRead < _length)
                        return Content.Chunk.asChunk(byteBuffer, false, _buffer);
                    Content.Chunk last = Content.Chunk.asChunk(byteBuffer, true, _buffer);
                    lockedSetTerminal(Content.Chunk.EOF);
                    return last;
                }
                if (read == 0)
                    return null;
                lockedSetTerminal(Content.Chunk.EOF);
                return Content.Chunk.EOF;
            }
            catch (Throwable t)
            {
                Content.Chunk failure = Content.Chunk.from(t, true);
                lockedSetTerminal(failure);
                return failure;
            }
        }
    }

    protected Content.Chunk skipToOffset() throws IOException
    {
        ByteBuffer byteBuffer = _buffer.getByteBuffer();
        if (_offsetRemaining > 0)
        {
            // Discard all bytes read until we reach the staring offset.
            while (_offsetRemaining > 0)
            {
                BufferUtil.clearToFill(byteBuffer);
                byteBuffer.limit((int)Math.min(_buffer.capacity(), _offsetRemaining));
                int read = _byteChannel.read(byteBuffer);
                if (read < 0)
                {
                    lockedSetTerminal(Content.Chunk.EOF);
                    return Content.Chunk.EOF;
                }
                if (read == 0)
                    return null;
                _offsetRemaining -= read;
            }
        }
        return Content.Chunk.EMPTY;
    }

    @Override
    public void fail(Throwable failure)
    {
        try (AutoLock ignored = lock())
        {
            lockedSetTerminal(Content.Chunk.from(failure, true));
        }
    }
}
