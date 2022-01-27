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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.FutureCallback;

/**
 * <p>A {@link Request.Content} that provides content asynchronously through an {@link OutputStream}
 * similar to {@link AsyncRequestContent}.</p>
 * <p>{@link OutputStreamRequestContent} can only be used in conjunction with
 * {@link Request#send(Response.CompleteListener)} (and not with its blocking counterpart
 * {@link Request#send()}) because it provides content asynchronously.</p>
 * <p>Content must be provided by writing to the {@link #getOutputStream() output stream}
 * that must be {@link OutputStream#close() closed} when all content has been provided.</p>
 * <p>Example usage:</p>
 * <pre>
 * HttpClient httpClient = ...;
 *
 * // Use try-with-resources to autoclose the output stream.
 * OutputStreamRequestContent content = new OutputStreamRequestContent();
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
 *
 *     // Even later...
 *     output.write("more content".getBytes());
 * } // Implicit call to output.close().
 * </pre>
 */
public class OutputStreamRequestContent extends AsyncRequestContent
{
    private final AsyncOutputStream output;

    public OutputStreamRequestContent()
    {
        this("application/octet-stream");
    }

    public OutputStreamRequestContent(String contentType)
    {
        super(contentType);
        this.output = new AsyncOutputStream();
    }

    public OutputStream getOutputStream()
    {
        return output;
    }

    private class AsyncOutputStream extends OutputStream
    {
        @Override
        public void write(int b) throws IOException
        {
            write(new byte[]{(byte)b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            try
            {
                FutureCallback callback = new FutureCallback();
                offer(ByteBuffer.wrap(b, off, len), callback);
                callback.get();
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
            catch (ExecutionException x)
            {
                throw new IOException(x.getCause());
            }
        }

        @Override
        public void flush() throws IOException
        {
            OutputStreamRequestContent.this.flush();
        }

        @Override
        public void close()
        {
            OutputStreamRequestContent.this.close();
        }
    }
}
