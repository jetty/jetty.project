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
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
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
    private long _totalRead;
    private Runnable demandCallback;
    private Content.Chunk _terminal;

    public ByteChannelContentSource(SeekableByteChannel seekableByteChannel, long offset, long length)
    {
        this(null, seekableByteChannel, offset, length);
    }

    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, SeekableByteChannel seekableByteChannel, long offset, long length)
    {
        this(byteBufferPool, (ByteChannel)seekableByteChannel, offset, length);
        if (offset >= 0 && seekableByteChannel != null)
        {
            try
            {
                seekableByteChannel.position(offset);
            }
            catch (IOException e)
            {
                // lock not needed in constructor
                lockedSetTerminal(Content.Chunk.from(e, true));
            }
        }
    }

    public ByteChannelContentSource(ByteChannel byteChannel)
    {
        this(null, byteChannel, -1L, -1L);
    }

    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel)
    {
        this(byteBufferPool, byteChannel, -1L, -1L);
    }

    private ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel, long offset, long length)
    {
        _byteBufferPool = Objects.requireNonNullElse(byteBufferPool, ByteBufferPool.SIZED_NON_POOLING);
        _byteChannel = byteChannel;
        _offset = offset < 0 ? 0 : offset;
        _length = length;
    }

    protected ByteChannel open() throws IOException
    {
        return _byteChannel;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        try (AutoLock ignored = lock.lock())
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
        try (AutoLock ignored = lock.lock())
        {
            demandCallback = this.demandCallback;
            this.demandCallback = null;
        }
        if (demandCallback != null)
            ExceptionUtil.run(demandCallback, this::fail);
    }

    protected void lockedSetTerminal(Content.Chunk terminal)
    {
        if (_terminal == null)
            _terminal = Objects.requireNonNull(terminal);
        else
            ExceptionUtil.addSuppressedIfNotAssociated(_terminal.getFailure(), terminal.getFailure());
        IO.close(_byteChannel);
        if (_buffer != null)
            _buffer.release();
        _buffer = null;
    }

    private void lockedEnsureOpenOrTerminal()
    {
        if (_terminal == null && (_byteChannel == null || !_byteChannel.isOpen()))
        {
            try
            {
                _byteChannel = open();
                if (_byteChannel == null || !_byteChannel.isOpen())
                    lockedSetTerminal(Content.Chunk.from(new ClosedChannelException(), true));
                else if (_offset >= 0 && _byteChannel instanceof SeekableByteChannel seekableByteChannel)
                    seekableByteChannel.position(_offset);
            }
            catch (IOException e)
            {
                lockedSetTerminal(Content.Chunk.from(e, true));
            }
        }
    }

    @Override
    public Content.Chunk read()
    {
        try (AutoLock ignored = lock.lock())
        {
            lockedEnsureOpenOrTerminal();
            if (_terminal != null)
                return _terminal;

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
                ByteBuffer byteBuffer = _buffer.getByteBuffer();
                BufferUtil.clearToFill(byteBuffer);
                if (_length >= 0)
                    byteBuffer.limit((int)Math.min(_buffer.capacity(), _length - _totalRead));
                int read = _byteChannel.read(byteBuffer);
                BufferUtil.flipToFlush(byteBuffer, 0);
                if (read == 0)
                    return null;
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
                lockedSetTerminal(Content.Chunk.EOF);
            }
            catch (Throwable t)
            {
                lockedSetTerminal(Content.Chunk.from(t, true));
            }
        }
        return _terminal;
    }

    @Override
    public void fail(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            lockedSetTerminal(Content.Chunk.from(failure, true));
        }
    }

    @Override
    public long getLength()
    {
        return _length;
    }

    @Override
    public boolean rewind()
    {
        try (AutoLock ignored = lock.lock())
        {
            // We can remove terminal condition for a rewind that is likely to occur
            if (_terminal != null && !Content.Chunk.isFailure(_terminal) && (_byteChannel == null || _byteChannel instanceof SeekableByteChannel))
                _terminal = null;

            lockedEnsureOpenOrTerminal();
            if (_terminal != null || _byteChannel == null || !_byteChannel.isOpen())
                return false;

            if (_offset >= 0 && _byteChannel instanceof SeekableByteChannel seekableByteChannel)
            {
                try
                {
                    seekableByteChannel.position(_offset);
                    _totalRead = 0;
                    return true;
                }
                catch (Throwable t)
                {
                    lockedSetTerminal(Content.Chunk.from(t, true));
                }
            }
            return false;
        }
    }

    /**
     * A {@link ByteChannelContentSource} for a {@link Path}
     */
    public static class PathContentSource extends ByteChannelContentSource
    {
        private final Path _path;

        public PathContentSource(Path path)
        {
            this(null, path, 0, -1);
        }

        public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path)
        {
            this(byteBufferPool, path, 0, -1);
        }

        public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path, long offset, long length)
        {
            super(byteBufferPool, null, offset, length < 0 ? size(path) : length);
            _path = path;
        }

        public Path getPath()
        {
            return _path;
        }

        @Override
        protected ByteChannel open() throws IOException
        {
            return Files.newByteChannel(_path, StandardOpenOption.READ);
        }

        private static long size(Path path)
        {
            try
            {
                return Files.size(path);
            }
            catch (IOException e)
            {
                return -1L;
            }
        }
    }
}
