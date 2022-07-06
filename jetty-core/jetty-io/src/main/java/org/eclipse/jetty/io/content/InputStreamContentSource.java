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

import java.io.InputStream;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * A {@link Content.Source} that is backed by an {@link InputStream}.
 * <p>
 * Data is read from the {@link InputStream} into a buffer that is optionally acquired
 * from a {@link ByteBufferPool}, and converted to a {@link Content.Chunk} that is
 * returned from {@link #read()}.   If no {@link ByteBufferPool} is provided, then
 * a {@link NullByteBufferPool} is used.
 * </p>
 *
 */
public class InputStreamContentSource implements Content.Source
{
    private final AutoLock lock = new AutoLock();
    private final SerializedInvoker invoker = new SerializedInvoker();
    private final InputStream inputStream;
    private final ByteBufferPool bufferPool;
    private int bufferSize = 4096;
    private Runnable demandCallback;
    private Content.Chunk.Error errorChunk;
    private boolean closed;

    public InputStreamContentSource(InputStream inputStream)
    {
        this(inputStream, null);
    }

    public InputStreamContentSource(InputStream inputStream, ByteBufferPool bufferPool)
    {
        this.inputStream = inputStream;
        this.bufferPool = bufferPool == null ? ByteBufferPool.NULL_POOL : bufferPool;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    @Override
    public Content.Chunk read()
    {
        try (AutoLock ignored = lock.lock())
        {
            if (errorChunk != null)
                return errorChunk;
            if (closed)
                return Content.Chunk.EOF;
        }

        try
        {
            ByteBuffer buffer = bufferPool.acquire(getBufferSize(), false);
            int read = inputStream.read(buffer.array(), buffer.arrayOffset(), buffer.capacity());
            if (read < 0)
            {
                close();
                return Content.Chunk.EOF;
            }
            else
            {
                buffer.limit(read);
                return Content.Chunk.from(buffer, false, bufferPool::release);
            }
        }
        catch (Throwable x)
        {
            return failure(x);
        }
    }

    private void close()
    {
        try (AutoLock ignored = lock.lock())
        {
            closed = true;
        }
        IO.close(inputStream);
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
        Content.Chunk error;
        try (AutoLock ignored = lock.lock())
        {
            error = errorChunk;
            if (error == null)
                error = errorChunk = Content.Chunk.from(failure);
        }
        IO.close(inputStream);
        return error;
    }
}
