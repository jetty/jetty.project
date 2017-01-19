//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.AsyncContentProvider;
import org.eclipse.jetty.client.Synchronizable;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

/**
 * A {@link ContentProvider} that allows to add content after {@link Request#send(Response.CompleteListener)}
 * has been called, therefore providing the request content at a later time.
 * <p>
 * {@link DeferredContentProvider} can only be used in conjunction with
 * {@link Request#send(Response.CompleteListener)} (and not with its blocking counterpart {@link Request#send()})
 * because it provides content asynchronously.
 * <p>
 * The deferred content is provided once and then fully consumed.
 * Invocations to the {@link #iterator()} method after the first will return an "empty" iterator
 * because the stream has been consumed on the first invocation.
 * However, it is possible for subclasses to override {@link #offer(ByteBuffer)} and {@link #close()} to copy
 * the content to another location (for example a file) and be able to support multiple invocations
 * of of {@link #iterator()} returning the iterator provided by this
  * class on the first invocation, and an iterator on the bytes copied to the other location
  * for subsequent invocations.
 * <p>
 * Typical usage of {@link DeferredContentProvider} is in asynchronous proxies, where HTTP headers arrive
 * separately from HTTP content chunks.
 * <p>
 * The deferred content must be provided through {@link #offer(ByteBuffer)}, which can be invoked multiple
 * times, and when all content has been provided it must be signaled with a call to {@link #close()}.
 * <p>
 * Example usage:
 * <pre>
 * HttpClient httpClient = ...;
 *
 * // Use try-with-resources to autoclose DeferredContentProvider
 * try (DeferredContentProvider content = new DeferredContentProvider())
 * {
 *     httpClient.newRequest("localhost", 8080)
 *             .content(content)
 *             .send(new Response.CompleteListener()
 *             {
 *                 &#64;Override
 *                 public void onComplete(Result result)
 *                 {
 *                     // Your logic here
 *                 }
 *             });
 *
 *     // At a later time...
 *     content.offer(ByteBuffer.wrap("some content".getBytes()));
 * }
 * </pre>
 */
public class DeferredContentProvider implements AsyncContentProvider, Callback, Closeable
{
    private static final Chunk CLOSE = new Chunk(BufferUtil.EMPTY_BUFFER, Callback.NOOP);

    private final Object lock = this;
    private final Deque<Chunk> chunks = new ArrayDeque<>();
    private final AtomicReference<Listener> listener = new AtomicReference<>();
    private final DeferredContentProviderIterator iterator = new DeferredContentProviderIterator();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long length = -1;
    private int size;
    private Throwable failure;

    /**
     * Creates a new {@link DeferredContentProvider} with the given initial content
     *
     * @param buffers the initial content
     */
    public DeferredContentProvider(ByteBuffer... buffers)
    {
        for (ByteBuffer buffer : buffers)
            offer(buffer);
    }

    @Override
    public void setListener(Listener listener)
    {
        if (!this.listener.compareAndSet(null, listener))
            throw new IllegalStateException(String.format("The same %s instance cannot be used in multiple requests",
                    AsyncContentProvider.class.getName()));

        if (isClosed())
        {
            synchronized (lock)
            {
                long total = 0;
                for (Chunk chunk : chunks)
                    total += chunk.buffer.remaining();
                length = total;
            }
        }
    }

    @Override
    public long getLength()
    {
        return length;
    }

    /**
     * Adds the given content buffer to this content provider
     * and notifies the listener that content is available.
     *
     * @param buffer the content to add
     * @return true if the content was added, false otherwise
     */
    public boolean offer(ByteBuffer buffer)
    {
        return offer(buffer, Callback.NOOP);
    }

    public boolean offer(ByteBuffer buffer, Callback callback)
    {
        return offer(new Chunk(buffer, callback));
    }

    private boolean offer(Chunk chunk)
    {
        Throwable failure;
        boolean result = false;
        synchronized (lock)
        {
            failure = this.failure;
            if (failure == null)
            {
                result = chunks.offer(chunk);
                if (result && chunk != CLOSE)
                    ++size;
            }
        }
        if (failure != null)
            chunk.callback.failed(failure);
        else if (result)
            notifyListener();
        return result;
    }

    private void clear()
    {
        synchronized (lock)
        {
            chunks.clear();
        }
    }

    public void flush() throws IOException
    {
        synchronized (lock)
        {
            try
            {
                while (true)
                {
                    if (failure != null)
                        throw new IOException(failure);
                    if (size == 0)
                        break;
                    lock.wait();
                }
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
        }
    }

    /**
     * No more content will be added to this content provider
     * and notifies the listener that no more content is available.
     */
    public void close()
    {
        if (closed.compareAndSet(false, true))
            offer(CLOSE);
    }

    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public void failed(Throwable failure)
    {
        iterator.failed(failure);
    }

    private void notifyListener()
    {
        Listener listener = this.listener.get();
        if (listener != null)
            listener.onContent();
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return iterator;
    }

    private class DeferredContentProviderIterator implements Iterator<ByteBuffer>, Callback, Synchronizable
    {
        private Chunk current;

        @Override
        public boolean hasNext()
        {
            synchronized (lock)
            {
                return chunks.peek() != CLOSE;
            }
        }

        @Override
        public ByteBuffer next()
        {
            synchronized (lock)
            {
                Chunk chunk = current = chunks.poll();
                if (chunk == CLOSE)
                {
                    // Slow path: reinsert the CLOSE chunk
                    // so that hasNext() works correctly.
                    chunks.offerFirst(CLOSE);
                    throw new NoSuchElementException();
                }
                return chunk == null ? null : chunk.buffer;
            }
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void succeeded()
        {
            Chunk chunk;
            synchronized (lock)
            {
                chunk = current;
                if (chunk != null)
                {
                    --size;
                    lock.notify();
                }
            }
            if (chunk != null)
                chunk.callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            List<Chunk> chunks = new ArrayList<>();
            synchronized (lock)
            {
                failure = x;
                // Transfer all chunks to fail them all.
                Chunk chunk = current;
                current = null;
                if (chunk != null)
                    chunks.add(chunk);
                chunks.addAll(DeferredContentProvider.this.chunks);
                clear();
                lock.notify();
            }
            for (Chunk chunk : chunks)
                chunk.callback.failed(x);
        }

        @Override
        public Object getLock()
        {
            return lock;
        }
    }

    public static class Chunk
    {
        public final ByteBuffer buffer;
        public final Callback callback;

        public Chunk(ByteBuffer buffer, Callback callback)
        {
            this.buffer = Objects.requireNonNull(buffer);
            this.callback = Objects.requireNonNull(callback);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
        }
    }
}
