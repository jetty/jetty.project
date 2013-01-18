//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.client.AsyncContentProvider;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

/**
 * A {@link ContentProvider} that allows to add content after {@link Request#send(Response.CompleteListener)}
 * has been called, therefore providing the request content at a later time.
 * <p />
 * The deferred content is provided once and then fully consumed.
 * Invocations to the {@link #iterator()} method after the first will return an "empty" iterator
 * because the stream has been consumed on the first invocation.
 * However, it is possible for subclasses to override {@link #offer(ByteBuffer)} and {@link #close()} to copy
 * the content to another location (for example a file) and be able to support multiple invocations
 * of of {@link #iterator()} returning the iterator provided by this
  * class on the first invocation, and an iterator on the bytes copied to the other location
  * for subsequent invocations.
 * <p />
 * Typical usage of {@link DeferredContentProvider} is in asynchronous proxies, where HTTP headers arrive
 * separately from HTTP content chunks.
 * <p />
 * The deferred content must be provided through {@link #offer(ByteBuffer)}, which can be invoked multiple
 * times, and when all content has been provided it must be signaled with a call to {@link #close()}.
 * <p />
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
 *                 &#64Override
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
public class DeferredContentProvider implements AsyncContentProvider, AutoCloseable
{
    private final Queue<ByteBuffer> queue = new ConcurrentLinkedQueue<>();
    private volatile Listener listener;
    private volatile boolean closed;

    /**
     * Creates a new {@link DeferredContentProvider} with the given initial content
     *
     * @param buffers the initial content
     */
    public DeferredContentProvider(ByteBuffer... buffers)
    {
        for (ByteBuffer buffer : buffers)
            queue.offer(buffer);
    }

    @Override
    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    @Override
    public long getLength()
    {
        return -1;
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
        boolean result = queue.offer(buffer);
        notifyListener(false);
        return result;
    }

    /**
     * No more content will be added to this content provider
     * and notifies the listener that no more content is available.
     */
    public void close()
    {
        closed = true;
        notifyListener(true);
    }

    private void notifyListener(boolean last)
    {
        Listener listener = this.listener;
        if (listener != null)
            listener.onContent(last);
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new Iterator<ByteBuffer>()
        {
            @Override
            public boolean hasNext()
            {
                return !queue.isEmpty() || !closed;
            }

            @Override
            public ByteBuffer next()
            {
                return queue.poll();
            }

            @Override
            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }
}
