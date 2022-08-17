//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * <p>A {@link Content.Source} that provides the file content of the passed {@link Path}.</p>
 */
public class PathContentSource implements Content.Source
{
    private final AutoLock lock = new AutoLock();
    private final SerializedInvoker invoker = new SerializedInvoker();
    private final Path path;
    private final long length;
    private final RetainableByteBufferPool byteBufferPool;
    private int bufferSize = 4096;
    private boolean useDirectByteBuffers = true;
    private SeekableByteChannel channel;
    private long totalRead;
    private Runnable demandCallback;
    private Content.Chunk.Error errorChunk;

    public PathContentSource(Path path)
    {
        this(path, (ByteBufferPool)null);
    }

    public PathContentSource(Path path, ByteBufferPool byteBufferPool)
    {
        this(path, (byteBufferPool == null ? ByteBufferPool.NOOP : byteBufferPool).asRetainableByteBufferPool());
    }

    public PathContentSource(Path path, RetainableByteBufferPool byteBufferPool)
    {
        try
        {
            if (!Files.isRegularFile(path))
                throw new NoSuchFileException(path.toString());
            if (!Files.isReadable(path))
                throw new AccessDeniedException(path.toString());
            this.path = path;
            this.length = Files.size(path);
            this.byteBufferPool = byteBufferPool == null ? ByteBufferPool.NOOP.asRetainableByteBufferPool() : byteBufferPool;
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

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

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
        ByteBuffer byteBuffer = retainableByteBuffer.getBuffer();

        int read;
        try
        {
            BufferUtil.clearToFill(byteBuffer);
            read = read(channel, byteBuffer);
            BufferUtil.flipToFlush(byteBuffer, 0);
        }
        catch (Throwable x)
        {
            return failure(x);
        }

        if (read > 0)
            totalRead += read;

        boolean last = isReadComplete(totalRead);
        if (last)
            IO.close(channel);

        return Content.Chunk.from(byteBuffer, last, retainableByteBuffer);
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
            runDemandCallback(demandCallback);
    }

    private void runDemandCallback(Runnable demandCallback)
    {
        try
        {
            demandCallback.run();
        }
        catch (Throwable x)
        {
            fail(x);
        }
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
