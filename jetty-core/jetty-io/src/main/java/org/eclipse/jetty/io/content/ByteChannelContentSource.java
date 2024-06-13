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
    private final SerializedInvoker _invoker = new SerializedInvoker();
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
        this(new ByteBufferPool.Sized(null), seekableByteChannel, offset, length);
    }

    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, SeekableByteChannel seekableByteChannel, long offset, long length)
    {
        this(byteBufferPool, (ByteChannel)seekableByteChannel, offset, length);
        if (offset > 0 && seekableByteChannel != null)
        {
            try
            {
                seekableByteChannel.position(offset);
            }
            catch (IOException e)
            {
                _terminal = Content.Chunk.from(e, true);
            }
        }
    }

    public ByteChannelContentSource(ByteChannel byteChannel)
    {
        this(new ByteBufferPool.Sized(null), byteChannel, -1L, -1L);
    }

    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel)
    {
        this(byteBufferPool, byteChannel, -1L, -1L);
    }

    private ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel, long offset, long length)
    {
        _byteBufferPool = Objects.requireNonNull(byteBufferPool);
        _byteChannel = byteChannel;
        _offset = offset < 0 ? 0 : offset;
        _length = length;
        if (_length >= 0 && _offset > _length)
            throw new IllegalArgumentException("offset greater than length");
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

    private void checkOpenLocked()
    {
        if (_terminal == null)
        {
            if (_byteChannel == null || !_byteChannel.isOpen())
            {
                try
                {
                    _byteChannel = open();
                    if (_byteChannel == null || !_byteChannel.isOpen())
                        _terminal = Content.Chunk.from(new ClosedChannelException(), true);
                    if (_offset > 0 && _byteChannel instanceof SeekableByteChannel seekableByteChannel)
                        seekableByteChannel.position(_offset);
                }
                catch (IOException e)
                {
                    _terminal = Content.Chunk.from(e, true);
                }

                if (_terminal != null && _buffer != null)
                {
                    _buffer.release();
                    _buffer = null;
                }
            }
        }
    }

    @Override
    public Content.Chunk read()
    {
        try (AutoLock ignored = lock.lock())
        {
            checkOpenLocked();
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
                    if (_length < 0 || _totalRead < _length)
                    {
                        _buffer.retain();
                        return Content.Chunk.asChunk(byteBuffer, false, _buffer);
                    }

                    _terminal = Content.Chunk.EOF;
                    IO.close(_byteChannel);
                    Content.Chunk last = Content.Chunk.asChunk(byteBuffer, true, _buffer);
                    _buffer = null;
                    return last;
                }
                _buffer.release();
                _buffer = null;
                _terminal = Content.Chunk.EOF;
                IO.close(_byteChannel);
            }
            catch (Throwable t)
            {
                _terminal = Content.Chunk.from(t, true);
            }
        }
        return _terminal;
    }

    @Override
    public void fail(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (_terminal == null)
                _terminal = Content.Chunk.from(failure, true);
            else
                ExceptionUtil.addSuppressedIfNotAssociated(_terminal.getFailure(), failure);
            IO.close(_byteChannel);
            if (_buffer != null)
            {
                _buffer.release();
                _buffer = null;
            }
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
            checkOpenLocked();
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
                    _terminal = Content.Chunk.from(t, true);
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
            super(new ByteBufferPool.Sized(null), null, 0, size(path));
            _path = path;
        }

        public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path)
        {
            super(byteBufferPool, null, 0, size(path));
            _path = path;
        }

        public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path, long offset, long length)
        {
            super(byteBufferPool, null, offset, length);
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
