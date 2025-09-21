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
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingNestedCallback;
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
     * Create a {@link ByteChannelContentSource} which reads from a {@link ByteChannel}.
     * @param byteBufferPool The {@link org.eclipse.jetty.io.ByteBufferPool.Sized} to use for any internal buffers.
     * @param byteChannel The {@link ByteChannel}s to use as the source.
     */
    public ByteChannelContentSource(ByteBufferPool.Sized byteBufferPool, ByteChannel byteChannel)
    {
        this(byteBufferPool, byteChannel, 0L, -1L);
    }

    /**
     * Create a {@link ByteChannelContentSource} which reads from a {@link ByteChannel}.
     * If the {@link ByteChannel} is an instance of {@link SeekableByteChannel} the implementation will use
     * {@link SeekableByteChannel#position(long)} to navigate to the starting offset.
     * @param byteBufferPool The {@link org.eclipse.jetty.io.ByteBufferPool.Sized} to use for any internal buffers.
     * @param byteChannel The {@link ByteChannel}s to use as the source.
     * @param offset the offset byte of the content to start from.
     *               Must be greater than or equal to 0 and less than the content length (if known).
     * @param length the length of the content to make available, -1 for the full length.
     *               If the size of the content is known, the length may be truncated to the content size minus the offset.
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

    private void lockedEnsureOpenOrTerminal()
    {
        assert lock.isHeldByCurrentThread();
        if (_terminal == null && (_byteChannel == null || !_byteChannel.isOpen()))
        {
            try
            {
                _byteChannel = open();
                if (_byteChannel == null || !_byteChannel.isOpen())
                {
                    lockedSetTerminal(Content.Chunk.from(new ClosedChannelException(), true));
                }
                else if (_byteChannel instanceof SeekableByteChannel seekableByteChannel)
                {
                    seekableByteChannel.position(_offset);
                    _offsetRemaining = 0;
                }
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
                            return _terminal;
                        }
                        if (read == 0)
                            return null;

                        _offsetRemaining -= read;
                    }
                }

                BufferUtil.clearToFill(byteBuffer);
                if (_length > 0)
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
    public void writeTo(Content.Sink sink, long length, Callback callback)
    {
        Throwable failure = null;
        try (AutoLock ignored = lock.lock())
        {
            lockedEnsureOpenOrTerminal();
            if (Content.Chunk.isFailure(_terminal))
                failure = _terminal.getFailure();
        }
        if (failure != null)
        {
            callback.failed(failure);
            return;
        }

        if (_byteChannel instanceof FileChannel fileChannel)
        {
            // TODO:
            //  if (sink instanceof SocketChannelEndPoint socketEndPoint)
            //  this is not the case ever, we have a Response here.
            //  We do not want to have another virtual write() method in Sink
            //  for the well-known wrapping reasons, so we cannot have:
            //  if (response instanceof Transferable t) t.writeTo()
            //  because that would be another virtual method.
            //  UNLESS we can lift this restriction!
            //  We will need to write the response line and headers anyway,
            //  so this call needs to happen later, not from Response.
            //  Therefore we need to pass the Content.Source to Response.
            //  We can do this: make Sink implement Attachable:
            //  sink.setAttachment(fileChannel)
            //  sink.write(true, null, callback);
            //  The attachment is propagated inwards to the innermost
            //  Sink, likely ChannelResponse; ChannelResponse forwards
            //  the attachment to MetaData.Response, so now we have it
            //  available in HttpConnection.SendCallback, but in general
            //  in other generators as well such as HTTP/2, because now
            //  we just rely on MetaData.Response.
            //  Then, in HttpConnection.SendCallback we need to generate
            //  the headers and flush them; after that, we know whether
            //  there is an attachment, and process() can skip calling
            //  the generator and just call fileChannel.transferTo().
            //  First step would be to support only content-length framing
            //  not chunked, although probably also chunked can be done
            //  (I guess with just one chunk per transfer).
            //  For content-length, we would still need to iterate through
            //  transferTo(), but that's easy with an IteratingCallback,
            //  just here inside this method!
            //
            // TODO: For HTTP/2 (and HTTP/1 chunked), we would need to
            //  generate the chunk, flush it, then transferTo(), then
            //  again generate chunk, flush it, then transferTo(), etc.
            //  HTTP/2 needs to cap by frameSize and flowControl, the
            //  position and count needs to be remembered, likely
            //  externally, but then this method would need to be
            //  writeTo(Sink, pos, cnt, Callback), that however needs to
            //  be reconciliated with the constructor offset and length.

            if (sink instanceof EndPoint endPoint)
            {
                if (endPoint.getTransport() instanceof SocketChannel socket)
                {
                    new Transferrer(fileChannel, _offset, length, socket, callback).iterate();
                    return;
                }
            }
        }

        Content.Source.super.writeTo(sink, length, callback);
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
            // We can only rewind if we have a SeekableByteChannel.
            if (!(_byteChannel instanceof SeekableByteChannel))
                return false;

            // We can remove terminal condition for a rewind that is likely to occur
            if (_terminal != null && !Content.Chunk.isFailure(_terminal) && (_byteChannel == null || _byteChannel instanceof SeekableByteChannel))
                _terminal = null;

            lockedEnsureOpenOrTerminal();
            if (_terminal != null || _byteChannel == null || !_byteChannel.isOpen())
                return false;

            try
            {
                ((SeekableByteChannel)_byteChannel).position(_offset);
                _offsetRemaining = 0;
                _totalRead = 0;
                return true;
            }
            catch (Throwable t)
            {
                lockedSetTerminal(Content.Chunk.from(t, true));
            }

            return true;
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
            this(null, path, 0L, -1L);
        }

        public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path)
        {
            this(byteBufferPool, path, 0L, -1L);
        }

        public PathContentSource(ByteBufferPool.Sized byteBufferPool, Path path, long offset, long length)
        {
            super(byteBufferPool, null, offset, TypeUtil.checkOffsetLengthSize(offset, length, size(path)));
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

    private static class Transferrer extends IteratingNestedCallback
    {
        private final FileChannel fileChannel;
        private final long position;
        private final long length;
        private final SocketChannel socketChannel;
        private long transferred;

        private Transferrer(FileChannel fileChannel, long position, long length, SocketChannel socketChannel, Callback callback)
        {
            super(callback);
            this.fileChannel = fileChannel;
            this.position = position;
            this.length = length;
            this.socketChannel = socketChannel;
        }

        @Override
        protected Action process() throws Throwable
        {
            transferred += fileChannel.transferTo(position + transferred, length - transferred, socketChannel);
            if (transferred == length)
                return Action.SUCCEEDED;
            return Action.SCHEDULED;
        }
    }
}
