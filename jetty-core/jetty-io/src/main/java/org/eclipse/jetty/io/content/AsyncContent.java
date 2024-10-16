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

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.io.internal.ByteBufferChunk;
import org.eclipse.jetty.util.BufferUtil;
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
    private static final AsyncChunk ASYNC_EOF = new AsyncChunk(true, BufferUtil.EMPTY_BUFFER, Callback.NOOP)
    {
        @Override
        public String toString()
        {
            return "ASYNC_EOF";
        }
    };

    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final SerializedInvoker invoker = new SerializedInvoker(AsyncContent.class);
    private final Queue<Content.Chunk> chunks = new ArrayDeque<>();
    private Content.Chunk persistentFailure;
    private boolean readClosed;
    private boolean writeClosed;
    private Runnable demandCallback;
    private long length = UNDETERMINED_LENGTH;

    /**
     * {@inheritDoc}
     * <p>The write completes:</p>
     * <ul>
     * <li>immediately with a failure when this instance is closed or already has a failure</li>
     * <li>successfully when a non empty {@link Content.Chunk} returned by {@link #read()} is released</li>
     * <li>successfully just before the {@link Content.Chunk} is returned by {@link #read()},
     * for any empty chunk {@link Content.Chunk}.</li>
     * </ul>
     */
    @Override
    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
    {
        offer(new AsyncChunk(last, byteBuffer, callback));
    }

    /**
     * The callback is stored to be failed in case fail() is called
     * or succeeded if and only if the chunk is terminal, as non-terminal
     * chunks have to bind the succeeding of the callback to their release.
     */
    private void offer(Content.Chunk chunk)
    {
        Throwable failure = null;
        boolean wasEmpty = false;
        try (AutoLock ignored = lock.lock())
        {
            if (writeClosed)
            {
                failure = new IOException("closed");
            }
            else if (persistentFailure != null)
            {
                failure = persistentFailure.getFailure();
            }
            else
            {
                wasEmpty = chunks.isEmpty();
                // No need to retain the chunk, because it's created internally
                // from a ByteBuffer and it will be released by the caller of read().
                chunks.offer(chunk);
                if (chunk.isLast())
                {
                    writeClosed = true;
                    if (length == UNDETERMINED_LENGTH)
                    {
                        length = 0;
                        for (Content.Chunk c : chunks)
                            length += c.remaining();
                    }
                }
            }
        }
        if (failure != null && chunk instanceof AsyncChunk asyncChunk)
            asyncChunk.failed(failure);
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
                if (persistentFailure != null)
                    throw new IOException(persistentFailure.getFailure());
                if (chunks.isEmpty())
                    return;
                // Special case for a last empty chunk that may not be read.
                if (writeClosed && chunks.size() == 1)
                {
                    Content.Chunk chunk = chunks.peek();
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
        offer(ASYNC_EOF);
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
        Content.Chunk current;
        try (AutoLock.WithCondition condition = lock.lock())
        {
            if (length == UNDETERMINED_LENGTH)
                length = -1;
            current = chunks.poll();
            if (current == null)
            {
                if (readClosed)
                    return Content.Chunk.EOF;
                if (persistentFailure != null)
                    return persistentFailure;
                return null;
            }
            readClosed = current.isLast();
            if (chunks.isEmpty())
                condition.signal();
        }

        // If the chunk is reference counted, the callback is succeeded when it is released.
        if (current.canRetain())
            return current;

        // If the chunk is not reference counted, we can succeed it now and return a chunk with a noop release.
        if (current instanceof AsyncChunk asyncChunk)
            asyncChunk.succeeded();

        if (Content.Chunk.isFailure(current))
            return current;

        return current.isLast() ? Content.Chunk.EOF : Content.Chunk.EMPTY;
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
            invoke = !chunks.isEmpty() || readClosed || persistentFailure != null;
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
        List<Content.Chunk> drained;
        try (AutoLock.WithCondition condition = lock.lock())
        {
            if (readClosed)
                return;
            if (persistentFailure != null)
                return;
            persistentFailure = Content.Chunk.from(failure);
            drained = List.copyOf(chunks);
            chunks.clear();
            condition.signal();
        }
        drained.forEach(c ->
        {
            if (c instanceof AsyncChunk ac)
                ac.failed(failure);
        });
        invoker.run(this::invokeDemandCallback);
    }

    @Override
    public void fail(Throwable failure, boolean last)
    {
        if (last)
            fail(failure);
        else
            offer(Content.Chunk.from(failure, false));
    }

    public int count()
    {
        try (AutoLock ignored = lock.lock())
        {
            return chunks.size();
        }
    }

    private static class AsyncChunk extends ByteBufferChunk implements Callback
    {
        private final Callback callback;
        private final Retainable.ReferenceCounter referenceCounter;

        public AsyncChunk(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            super(byteBuffer.hasRemaining() ? byteBuffer : BufferUtil.EMPTY_BUFFER, last);
            this.callback = callback;
            referenceCounter = getByteBuffer() == BufferUtil.EMPTY_BUFFER ? null : new ReferenceCounter();
        }

        @Override
        public boolean canRetain()
        {
            return referenceCounter != null;
        }

        @Override
        public void retain()
        {
            if (canRetain())
                referenceCounter.retain();
        }

        @Override
        public boolean release()
        {
            if (!canRetain())
                return true;
            boolean released = referenceCounter.release();
            if (released)
                succeeded();
            return released;
        }

        @Override
        public void succeeded()
        {
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            callback.failed(x);
        }
    }
}
