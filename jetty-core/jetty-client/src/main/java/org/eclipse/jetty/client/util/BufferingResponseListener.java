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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.Listener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.BufferUtil;

/**
 * <p>Implementation of {@link Listener} that buffers the content up to a maximum length
 * specified to the constructors.</p>
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via {@link #getContent()} or {@link #getContentAsString()}.</p>
 * <p>Instances of this class are not reusable, so one must be allocated for each request.</p>
 */
public abstract class BufferingResponseListener extends Listener.Adapter
{
    private final int maxLength;
    private ByteBuffer buffer;
    private String mediaType;
    private String encoding;

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
        if (maxLength < 0)
            throw new IllegalArgumentException("Invalid max length " + maxLength);
        this.maxLength = maxLength;
    }

    @Override
    public void onHeaders(Response response)
    {
        super.onHeaders(response);

        Request request = response.getRequest();
        HttpFields headers = response.getHeaders();
        long length = headers.getLongField(HttpHeader.CONTENT_LENGTH);
        if (HttpMethod.HEAD.is(request.getMethod()))
            length = 0;
        if (length > maxLength)
        {
            response.abort(new IllegalArgumentException("Buffering capacity " + maxLength + " exceeded"));
            return;
        }

        String contentType = headers.get(HttpHeader.CONTENT_TYPE);
        if (contentType != null)
        {
            String media = contentType;

            String charset = "charset=";
            int index = contentType.toLowerCase(Locale.ENGLISH).indexOf(charset);
            if (index > 0)
            {
                media = contentType.substring(0, index);
                String encoding = contentType.substring(index + charset.length());
                // Sometimes charsets arrive with an ending semicolon.
                int semicolon = encoding.indexOf(';');
                if (semicolon > 0)
                    encoding = encoding.substring(0, semicolon).trim();
                // Sometimes charsets are quoted.
                int lastIndex = encoding.length() - 1;
                if (encoding.charAt(0) == '"' && encoding.charAt(lastIndex) == '"')
                    encoding = encoding.substring(1, lastIndex).trim();
                this.encoding = encoding;
            }

            int semicolon = media.indexOf(';');
            if (semicolon > 0)
                media = media.substring(0, semicolon).trim();
            this.mediaType = media;
        }
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        int length = content.remaining();
        if (length > BufferUtil.space(buffer))
        {
            int remaining = buffer == null ? 0 : buffer.remaining();
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

    public String getMediaType()
    {
        return mediaType;
    }

    public String getEncoding()
    {
        return encoding;
    }

    /**
     * @return the content as bytes
     * @see #getContentAsString()
     */
    public byte[] getContent()
    {
        if (buffer == null)
            return new byte[0];
        return BufferUtil.toArray(buffer);
    }

    /**
     * @return the content as a string, using the "Content-Type" header to detect the encoding
     * or defaulting to UTF-8 if the encoding could not be detected.
     * @see #getContentAsString(String)
     */
    public String getContentAsString()
    {
        String encoding = this.encoding;
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
