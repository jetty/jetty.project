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

package org.eclipse.jetty.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.client.Response.Listener;
import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>Implementation of {@link Listener} that buffers the content up to a maximum length
 * specified to the constructors.</p>
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via {@link #getContent()} or {@link #getContentAsString()}.</p>
 * <p>Instances of this class are not reusable, so one must be allocated for each request.</p>
 * <p>The implementation is not very efficient, as it copies (possibly multiple times)
 * the response content into a buffer.
 * Use {@link RetainingResponseListener} for a more efficient implementation.</p>
 *
 * @deprecated use {@link RetainingResponseListener} instead
 */
@Deprecated(since = "12.1.0", forRemoval = true)
public abstract class BufferingResponseListener extends AbstractResponseListener
{
    private ByteBuffer buffer;

    /**
     * Creates an instance with a default maximum length of 2 MiB.
     */
    public BufferingResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    /**
     * Creates an instance with the given maximum length
     *
     * @param maxLength the maximum length of the content
     */
    public BufferingResponseListener(int maxLength)
    {
        super(maxLength);
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        int length = content.remaining();
        if (length == 0)
            return;
        if (length > BufferUtil.space(buffer))
        {
            int remaining = buffer == null ? 0 : buffer.remaining();
            int maxLength = getMaxLength();
            if (remaining + length > maxLength)
                response.abort(new IllegalArgumentException("Buffering capacity " + maxLength + " exceeded"));
            int requiredCapacity = buffer == null ? length : buffer.capacity() + length;
            int newCapacity = Math.min(Integer.highestOneBit(requiredCapacity) << 1, maxLength);
            buffer = BufferUtil.ensureCapacity(buffer, newCapacity);
        }
        BufferUtil.append(buffer, content);
    }

    @Override
    public abstract void onComplete(Result result);

    /**
     * @return the content as bytes
     * @see #getContentAsString()
     */
    public byte[] getContent()
    {
        if (buffer == null)
            return BufferUtil.EMPTY_BYTES;
        return BufferUtil.toArray(buffer);
    }

    /**
     * @return the content as a string, using the "Content-Type" header to detect the encoding
     * or defaulting to UTF-8 if the encoding could not be detected.
     * @see #getContentAsString(String)
     */
    public String getContentAsString()
    {
        String encoding = getEncoding();
        if (encoding == null)
            return getContentAsString(StandardCharsets.UTF_8);
        return getContentAsString(encoding);
    }

    /**
     * @param encoding the encoding of the content bytes
     * @return the content as a string, with the specified encoding
     * @see #getContentAsString()
     */
    public String getContentAsString(String encoding)
    {
        if (buffer == null)
            return null;
        return BufferUtil.toString(buffer, Charset.forName(encoding));
    }

    /**
     * @param encoding the encoding of the content bytes
     * @return the content as a string, with the specified encoding
     * @see #getContentAsString()
     */
    public String getContentAsString(Charset encoding)
    {
        if (buffer == null)
            return null;
        return BufferUtil.toString(buffer, encoding);
    }

    /**
     * @return Content as InputStream
     */
    public InputStream getContentAsInputStream()
    {
        if (buffer == null)
            return new ByteArrayInputStream(new byte[0]);
        return new ByteArrayInputStream(buffer.array(), buffer.arrayOffset(), buffer.remaining());
    }
}
