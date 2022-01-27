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

package org.eclipse.jetty.client.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.eclipse.jetty.client.AsyncContentProvider;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.Callback;

/**
 * A {@link ContentProvider} that provides content asynchronously through an {@link OutputStream}
 * similar to {@link DeferredContentProvider}.
 * <p>
 * {@link OutputStreamContentProvider} can only be used in conjunction with
 * {@link Request#send(Response.CompleteListener)} (and not with its blocking counterpart {@link Request#send()})
 * because it provides content asynchronously.
 * <p>
 * The deferred content is provided once by writing to the {@link #getOutputStream() output stream}
 * and then fully consumed.
 * Invocations to the {@link #iterator()} method after the first will return an "empty" iterator
 * because the stream has been consumed on the first invocation.
 * However, it is possible for subclasses to support multiple invocations of {@link #iterator()}
 * by overriding {@link #write(ByteBuffer)} and {@link #close()}, copying the bytes and making them
 * available for subsequent invocations.
 * <p>
 * Content must be provided by writing to the {@link #getOutputStream() output stream}, that must be
 * {@link OutputStream#close() closed} when all content has been provided.
 * <p>
 * Example usage:
 * <pre>
 * HttpClient httpClient = ...;
 *
 * // Use try-with-resources to autoclose the output stream
 * OutputStreamContentProvider content = new OutputStreamContentProvider();
 * try (OutputStream output = content.getOutputStream())
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
 *     output.write("some content".getBytes());
 * }
 * </pre>
 *
 * @deprecated use {@link OutputStreamRequestContent} instead
 */
@Deprecated
public class OutputStreamContentProvider implements AsyncContentProvider, Callback, Closeable
{
    private final DeferredContentProvider deferred = new DeferredContentProvider();
    private final OutputStream output = new DeferredOutputStream();

    @Override
    public InvocationType getInvocationType()
    {
        return deferred.getInvocationType();
    }

    @Override
    public long getLength()
    {
        return deferred.getLength();
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return deferred.iterator();
    }

    @Override
    public void setListener(Listener listener)
    {
        deferred.setListener(listener);
    }

    public OutputStream getOutputStream()
    {
        return output;
    }

    protected void write(ByteBuffer buffer)
    {
        deferred.offer(buffer);
    }

    @Override
    public void close()
    {
        deferred.close();
    }

    @Override
    public void succeeded()
    {
        deferred.succeeded();
    }

    @Override
    public void failed(Throwable failure)
    {
        deferred.failed(failure);
    }

    private class DeferredOutputStream extends OutputStream
    {
        @Override
        public void write(int b) throws IOException
        {
            write(new byte[]{(byte)b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            OutputStreamContentProvider.this.write(ByteBuffer.wrap(b, off, len));
            flush();
        }

        @Override
        public void flush() throws IOException
        {
            deferred.flush();
        }

        @Override
        public void close() throws IOException
        {
            OutputStreamContentProvider.this.close();
        }
    }
}
