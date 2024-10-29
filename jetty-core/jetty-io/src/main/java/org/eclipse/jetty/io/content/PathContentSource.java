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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * <p>A {@link Content.Source} that provides the file content of the passed {@link Path}.</p>
 */
public class PathContentSource implements Content.Source
{
    // TODO in 12.1.x reimplement this class based on ByteChannelContentSource

    private final AutoLock lock = new AutoLock();
    private final SerializedInvoker invoker = new SerializedInvoker(PathContentSource.class);
    private final Path path;
    private final long length;
    private final ByteBufferPool byteBufferPool;
    private int bufferSize;
    private boolean useDirectByteBuffers;
    private SeekableByteChannel channel;
    private long totalRead;
    private Runnable demandCallback;
    private Content.Chunk errorChunk;

    public PathContentSource(Path path)
    {
        this(path, null, true, -1);
    }

    public PathContentSource(Path path, ByteBufferPool byteBufferPool)
    {
        this(path,
            byteBufferPool instanceof ByteBufferPool.Sized sized ? sized.getWrapped() : byteBufferPool,
            byteBufferPool instanceof ByteBufferPool.Sized sized ? sized.isDirect() : true,
            byteBufferPool instanceof ByteBufferPool.Sized sized ? sized.getSize() : -1);
    }

    public PathContentSource(Path path, ByteBufferPool.Sized sizedBufferPool)
    {
        this(path,
            sizedBufferPool == null ? null : sizedBufferPool.getWrapped(),
            sizedBufferPool == null ? true : sizedBufferPool.isDirect(),
            sizedBufferPool == null ? -1 : sizedBufferPool.getSize());
    }

    private PathContentSource(Path path, ByteBufferPool byteBufferPool, boolean direct, int bufferSize)
    {
        try
        {
            if (!Files.isRegularFile(path))
                throw new NoSuchFileException(path.toString());
            if (!Files.isReadable(path))
                throw new AccessDeniedException(path.toString());
            this.path = path;
            this.length = Files.size(path);

            this.byteBufferPool = byteBufferPool != null ? byteBufferPool : ByteBufferPool.NON_POOLING;
            this.useDirectByteBuffers = direct;
            this.bufferSize = bufferSize > 0 ? bufferSize : 4096;
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x);
        }
    }

    public Path getPath()
    {
        return path;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * @param bufferSize The size of the buffer
     * @deprecated Use {@link InputStreamContentSource#InputStreamContentSource(InputStream, ByteBufferPool.Sized)}
     */
    @Deprecated(forRemoval = true)
    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    /**
     * @param useDirectByteBuffers {@code true} if direct buffers should be used
     * @deprecated Use {@link InputStreamContentSource#InputStreamContentSource(InputStream, ByteBufferPool.Sized)}
     */
    @Deprecated(forRemoval = true)
    public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
    {
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public Content.Chunk read()
    {
        SeekableByteChannel channel;
        try (AutoLock ignored = lock.lock())
        {
            if (errorChunk != null)
                return errorChunk;

            if (this.channel == null)
            {
                try
                {
                    this.channel = open();
                }
                catch (Throwable x)
                {
                    return failure(x);
                }
            }
            channel = this.channel;
        }

        if (!channel.isOpen())
            return Content.Chunk.EOF;

        RetainableByteBuffer retainableByteBuffer = byteBufferPool.acquire(getBufferSize(), isUseDirectByteBuffers());
        ByteBuffer byteBuffer = retainableByteBuffer.getByteBuffer();

        int read;
        try
        {
            BufferUtil.clearToFill(byteBuffer);
            read = read(channel, byteBuffer);
            BufferUtil.flipToFlush(byteBuffer, 0);
        }
        catch (Throwable x)
        {
            retainableByteBuffer.release();
            return failure(x);
        }

        if (read > 0)
            totalRead += read;

        boolean last = read == -1 || isReadComplete(totalRead);
        if (last)
            IO.close(channel);

        return Content.Chunk.asChunk(byteBuffer, last, retainableByteBuffer);
    }

    protected SeekableByteChannel open() throws IOException
    {
        return Files.newByteChannel(path, StandardOpenOption.READ);
    }

    protected int read(SeekableByteChannel channel, ByteBuffer byteBuffer) throws IOException
    {
        return channel.read(byteBuffer);
    }

    protected boolean isReadComplete(long read)
    {
        return read == getLength();
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
        invoker.run(this::invokeDemandCallback);
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

    @Override
    public void fail(Throwable failure)
    {
        failure(failure);
    }

    private Content.Chunk failure(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (errorChunk == null)
            {
                errorChunk = Content.Chunk.from(failure);
                IO.close(channel);
            }
            return errorChunk;
        }
        // Demands are always serviced immediately so there is no
        // need to ask the invoker to run invokeDemandCallback here.
    }

    @Override
    public boolean rewind()
    {
        try (AutoLock ignored = lock.lock())
        {
            IO.close(channel);
            channel = null;
            totalRead = 0;
            demandCallback = null;
            errorChunk = null;
        }
        return true;
    }
}
