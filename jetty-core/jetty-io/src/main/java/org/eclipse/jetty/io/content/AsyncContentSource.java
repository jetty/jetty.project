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
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.internal.BufferChunk;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/// A [Content.Source] that is offered content asynchronously.
///
/// The content [offered][#offer(boolean, RetainableByteBuffer, Callback)]
/// is converted to a [Content.Chunk] and made available by calling [#read()].
///
/// If necessary, any [Runnable] passed to the [#demand(Runnable)] method
/// is invoked once content is offered.
public class AsyncContentSource implements Content.Source, Closeable
{
    private static final int UNDETERMINED_LENGTH = -2;
    private static final AsyncChunk ASYNC_EOF = new AsyncChunk(true, RetainableByteBuffer.empty(), Callback.NOOP)
    {
        @Override
        public String toString()
        {
            return "ASYNC_EOF";
        }
    };

    private final AutoLock.WithCondition lock = new AutoLock.WithCondition();
    private final SerializedInvoker invoker = new SerializedInvoker(AsyncContentSource.class);
    private final Queue<AsyncChunk> chunks = new ArrayDeque<>();
    private Content.Chunk persistentFailure;
    private boolean readClosed;
    private boolean writeClosed;
    private Runnable demandCallback;
    private long length = UNDETERMINED_LENGTH;

    /// Offers the content bytes to be read.
    ///
    /// The [RetainableByteBuffer] content is retained and the code that calls
    /// [#read()] is responsible to release it.
    ///
    /// The [Callback] completes:
    /// * Immediately with a failure when this instance is closed or already has a failure.
    /// * Successfully just before the [Content.Chunk] is returned by [#read()].
    ///
    /// The [Callback] is notified _before_ the [RetainableByteBuffer] content is released.
    /// The code that offers the content must be aware that it cannot reuse the
    /// [RetainableByteBuffer] if it is still [retained][RetainableByteBuffer#isRetained()].
    ///
    /// Note that, while the signature is identical, the semantic of this method is different
    /// from [Content.Sink#write(boolean, RetainableByteBuffer, Callback)]:
    /// * In [Content.Sink#write(boolean, RetainableByteBuffer, Callback)], the callback is
    /// completed when the buffer has been written; the buffer is not in use anymore.
    /// * In this method, the callback is completed when the buffer is read; the buffer may
    /// still be in use.
    ///
    /// [RetainableByteBuffer#wrap(ByteBuffer, Runnable)] can be used by code that needs
    /// to be notified when the buffer has been fully released, for example when wrapping
    /// a `byte[]` or [ByteBuffer] that is reused across calls to this method.
    ///
    /// @param last whether the content is the last
    /// @param buffer the [RetainableByteBuffer] content
    /// @param callback the callback to notify when the content is read
    public void offer(boolean last, RetainableByteBuffer buffer, Callback callback)
    {
        try (AsyncChunk chunk = new AsyncChunk(last, buffer, callback))
        {
            offer(chunk);
        }
    }

    /// Offers the content string to be read.
    ///
    /// Delegates to [#offer(boolean, RetainableByteBuffer, Callback)].
    ///
    /// @param last whether the content is the last
    /// @param content the [String] content
    /// @param charset the [Charset] to use for encoding
    /// @param callback the callback to notify when the content is read
    public void offer(boolean last, String content, Charset charset, Callback callback)
    {
        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(content, charset))
        {
            offer(last, buffer, callback);
        }
    }

    private void offer(AsyncChunk chunk)
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

                chunk.retain();
                chunks.offer(chunk);

                if (chunk.isLast())
                {
                    writeClosed = true;
                    if (length == UNDETERMINED_LENGTH)
                    {
                        length = 0;
                        for (Content.Chunk c : chunks)
                        {
                            length += c.remaining();
                        }
                    }
                }
            }
        }
        if (failure != null)
            chunk.fail(failure);
        else if (wasEmpty)
            invoker.run(this::invokeDemandCallback);
    }

    /// Flushes all the offered content, waiting until all the content is [#read()].
    ///
    /// @throws IOException if this [Content.Source] is failed, or the current thread is interrupted
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

    /// Offers the last, empty, content.
    @Override
    public void close()
    {
        offer(ASYNC_EOF);
    }

    /// @return whether the last content was offered
    public boolean isClosed()
    {
        try (AutoLock ignored = lock.lock())
        {
            return writeClosed;
        }
    }

    /// @return the total number of bytes offered, or -1 if unknown
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
        AsyncChunk current;
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
        if (current == ASYNC_EOF)
            return Content.Chunk.EOF;

        // Succeed the callback now, before returning the chunk.
        current.succeed();

        if (current.hasRemaining() || Content.Chunk.isFailure(current))
            return current;

        // Readers may skip empty chunks without releasing them,
        // so release them here and return the chunk constants.
        current.release();
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
        List<AsyncChunk> drained;
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
            if (c != ASYNC_EOF)
            {
                c.fail(failure);
                c.release();
            }
        });
        invoker.run(this::invokeDemandCallback);
    }

    @Override
    public void fail(Throwable failure, boolean last)
    {
        if (last)
        {
            fail(failure);
        }
        else
        {
            offer(new AsyncChunk(false, RetainableByteBuffer.empty(), Callback.NOOP)
            {
                @Override
                public Throwable getFailure()
                {
                    return failure;
                }
            });
        }
    }

    /// @return the number of [RetainableByteBuffer]s yet to be read
    public int count()
    {
        try (AutoLock ignored = lock.lock())
        {
            return chunks.size();
        }
    }

    private static class AsyncChunk extends BufferChunk
    {
        private final Callback callback;

        private AsyncChunk(boolean last, RetainableByteBuffer buffer, Callback callback)
        {
            // Retains the buffer which is released when refCount goes to zero.
            super(buffer, last);
            this.callback = Objects.requireNonNull(callback);
        }

        private void succeed()
        {
            callback.succeeded();
        }

        private void fail(Throwable x)
        {
            callback.failed(x);
        }
    }
}
