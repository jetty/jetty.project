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

/**
 * <p>A {@link Content.Source} that is also a {@link Content.Sink}.
 * Content written to the {@link Content.Sink} is converted to a {@link Content.Chunk}
 * and made available to calls to the {@link #read()} method.  If necessary, any
 * {@link Runnable} passed to the {@link #demand(Runnable)} method is invoked once
 * content is written to the {@link Content.Sink}.</p>
 */
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

    /**
     * {@inheritDoc}
     * <p>The write completes:</p>
     * <ul>
     * <li>immediately with a failure when this instance is closed or already in error</li>
     * <li>successfully when the {@link Content.Chunk} returned by {@link #read()} is released</li>
     * <li>successfully just before the {@link Content.Chunk} is returned by {@link #read()},
     * if the chunk {@link Content.Chunk#canRetain() cannot be retained}</li>
     * </ul>
     */
    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        Content.Chunk chunk;
        if (byteBuffer.hasRemaining())
            chunk = Content.Chunk.from(byteBuffer, last, callback::succeeded);
        else
            chunk = last ? Content.Chunk.EOF : Content.Chunk.EMPTY;
        offer(chunk, callback);
    }

    /**
     * The callback is stored to be failed in case fail() is called
     * or succeeded if and only if the chunk is terminal, as non-terminal
     * chunks have to bind the succeeding of the callback to their release.
     */
    private void offer(Content.Chunk chunk, Callback callback)
    {
        if (chunk instanceof Content.Chunk.Error)
        {
            callback.failed(new IllegalArgumentException("Cannot not write Chunk.Error instances, call fail(Throwable) instead"));
            return;
        }

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
                // No need to retain the chunk, because it's created internally
                // from a ByteBuffer and it will be released by the caller of read().
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
        if (wasEmpty)
            invoker.run(this::invokeDemandCallback);
    }

    public void flush() throws IOException
    {
        try (AutoLock.WithCondition condition = lock.lock())
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
                condition.await();
            }
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }

    @Override
    public void close()
    {
        offer(Content.Chunk.EOF, Callback.NOOP);
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
        try (AutoLock.WithCondition condition = lock.lock())
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
                condition.signal();
        }
        if (!current.chunk().canRetain())
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
        try (AutoLock.WithCondition condition = lock.lock())
        {
            if (readClosed)
                return;
            if (errorChunk != null)
                return;
            errorChunk = Content.Chunk.from(failure);
            drained = List.copyOf(chunks);
            chunks.clear();
            condition.signal();
        }
        drained.forEach(cc -> cc.callback().failed(failure));
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
