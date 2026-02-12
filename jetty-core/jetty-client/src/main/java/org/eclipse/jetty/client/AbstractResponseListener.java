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
import java.util.Locale;
import java.util.Objects;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public abstract class AbstractResponseListener implements Response.Listener
{
    private final RetainableByteBuffer.Mutable accumulator;
    private String encoding;
    private String mediaType;
    private byte[] content;
    private boolean append;

    protected AbstractResponseListener(RetainableByteBuffer.Mutable accumulator)
    {
        this.accumulator = Objects.requireNonNull(accumulator);
    }

    public long getMaxLength()
    {
        return accumulator.maxSize();
    }

    public String getEncoding()
    {
        return encoding;
    }

    public String getMediaType()
    {
        return mediaType;
    }

    @Override
    public void onHeaders(Response response)
    {
        Request request = response.getRequest();
        HttpFields headers = response.getHeaders();
        long length = headers.getLongField(HttpHeader.CONTENT_LENGTH);
        if (HttpMethod.HEAD.is(request.getMethod()))
            length = 0;
        long maxLength = getMaxLength();
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
    public void onContent(Response response, Content.Chunk chunk, Runnable demander) throws Exception
    {
        onContent(response, chunk.getByteBuffer());
        if (append)
        {
            append = false;
            if (accumulator.append(chunk))
                demander.run();
            else
                response.abort(new IllegalArgumentException("Buffering capacity " + getMaxLength() + " exceeded"));
        }
        else
        {
            demander.run();
        }
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        // For backward compatibility, mark whether applications called
        // this method, which means they wanted to perform accumulation.
        append = true;
    }

    @Override
    public void onSuccess(Response response)
    {
        // Always take here to be sure the accumulator is released.
        content = take();
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        accumulator.clear();
    }

    /**
     * @return the content as a byte array.
     * @see #getContentAsString()
     */
    public byte[] getContent()
    {
        // Call take() in case onSuccess() is
        // overridden, but super is not called.
        if (content == null)
            content = take();
        return content;
    }

    /**
     * @return the content as a string, using the "Content-Type" header to detect
     * the encoding or defaulting to UTF-8 if the encoding could not be detected.
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
        return getContentAsString(Charset.forName(encoding));
    }

    /**
     * @param charset the charset of the content bytes
     * @return the content as a string, with the specified charset
     * @see #getContentAsString()
     */
    public String getContentAsString(Charset charset)
    {
        return new String(getContent(), charset);
    }

    /**
     * @return the content as {@link InputStream}
     */
    public InputStream getContentAsInputStream()
    {
        if (content != null)
            return new ByteArrayInputStream(content);
        return Content.Source.asInputStream(getContentAsContentSource());
    }

    /**
     * @return the content as {@link Content.Source}
     */
    public Content.Source getContentAsContentSource()
    {
        // Take the DynamicCapacity's content source only if the content hasn't been already taken.
        if (content == null && accumulator instanceof RetainableByteBuffer.DynamicCapacity dynamic)
        {
            // When the content is taken, further getContent* calls will find an empty content.
            content = BufferUtil.EMPTY_BYTES;
            return dynamic.takeContentSource();
        }
        return Content.Source.from(ByteBuffer.wrap(getContent()));
    }

    private byte[] take()
    {
        return accumulator.takeByteArray();
    }
}
