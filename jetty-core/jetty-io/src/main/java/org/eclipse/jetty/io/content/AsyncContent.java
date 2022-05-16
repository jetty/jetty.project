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

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

public class AsyncContent implements Content.Sink, Content.Source, Closeable
{
    private static final int UNDETERMINED_LENGTH = -2;

    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final SerializedInvoker invoker = new SerializedInvoker();
    private final Queue<ChunkCallback> chunks = new ArrayDeque<>();
    private Content.Chunk.Error errorChunk;
    private boolean readClosed;
    private boolean writeClosed;
    private Runnable demandCallback;
    private long length = UNDETERMINED_LENGTH;

    @Override
    public void write(boolean last, Callback callback, ByteBuffer... buffers)
    {
        for (int i = 0; i < buffers.length; ++i)
        {
            ByteBuffer buffer = buffers[i];
            boolean isLast = last && i == buffers.length - 1;
            write(Content.Chunk.from(buffer, isLast), Callback.NOOP);
        }
    }

    @Override
    public void write(Content.Chunk chunk, Callback callback)
    {
        Throwable failure = null;
        boolean wasEmpty = false;
        try (AutoLock ignored = lock.lock())
        {
            if (writeClosed)
            {
                failure = new IOException("closed");
            }
            else if (errorChunk != null)
            {
                failure = errorChunk.getCause();
            }
            else
            {
                wasEmpty = chunks.isEmpty();
                chunks.offer(new ChunkCallback(chunk, callback));
                if (chunk.isLast())
                {
                    writeClosed = true;
                    if (length == UNDETERMINED_LENGTH)
                        length = chunks.stream().mapToLong(cc -> cc.chunk().remaining()).sum();
                }
            }
        }
        if (failure != null)
            callback.failed(failure);
        else if (wasEmpty)
            invoker.run(this::invokeDemandCallback);
    }

    public void flush() throws IOException
    {
        try (AutoLock.WithCondition l = lock.lock())
        {
            try
            {
                while (true)
                {
                    // Always wrap the exception to make sure
                    // the stack trace comes from flush().
                    if (errorChunk != null)
                        throw new IOException(errorChunk.getCause());
                    if (chunks.isEmpty())
                        return;
                    // Special case for a last empty chunk that may not be read.
                    if (writeClosed && chunks.size() == 1)
                    {
                        Content.Chunk chunk = chunks.peek().chunk();
                        if (chunk.isLast() && !chunk.hasRemaining())
                            return;
                    }
                    l.await();
                }
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
        }
    }

    @Override
    public void close()
    {
        write(Content.Chunk.EOF, Callback.NOOP);
    }

    public boolean isClosed()
    {
        try (AutoLock ignored = lock.lock())
        {
            return writeClosed;
        }
    }

    @Override
    public long getLength()
    {
        try (AutoLock ignored = lock.lock())
        {
            return length < 0 ? -1 : length;
        }
    }

    @Override
    public Content.Chunk read()
    {
        ChunkCallback current;
        try (AutoLock.WithCondition l = lock.lock())
        {
            if (length == UNDETERMINED_LENGTH)
                length = -1;
            current = chunks.poll();
            if (current == null)
            {
                if (readClosed)
                    return Content.Chunk.EOF;
                if (errorChunk != null)
                    return errorChunk;
                return null;
            }
            readClosed = current.chunk().isLast();
            if (chunks.isEmpty())
                l.signal();
        }
        current.callback().succeeded();
        return current.chunk();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        boolean invoke;
        try (AutoLock ignored = lock.lock())
        {
            if (this.demandCallback != null)
                throw new IllegalStateException("demand pending");
            this.demandCallback = Objects.requireNonNull(demandCallback);
            invoke = !chunks.isEmpty() || readClosed || errorChunk != null;
        }
        if (invoke)
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
        List<ChunkCallback> drained;
        try (AutoLock ignored = lock.lock())
        {
            if (readClosed)
                return;
            if (errorChunk != null)
                return;
            errorChunk = Content.Chunk.from(failure);
            drained = List.copyOf(chunks);
            chunks.clear();
        }
        drained.forEach(cc ->
        {
            cc.chunk().release();
            cc.callback().failed(failure);
        });
        invoker.run(this::invokeDemandCallback);
    }

    public int count()
    {
        try (AutoLock ignored = lock.lock())
        {
            return chunks.size();
        }
    }

    private record ChunkCallback(Content.Chunk chunk, Callback callback)
    {
    }
}
