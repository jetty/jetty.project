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

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Response.Listener;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;

/**
 * <p>Implementation of {@link Listener} that buffers the content up to a maximum length
 * specified to the constructors.</p>
 * <p>The content may be retrieved from {@link #onSuccess(Response)} or {@link #onComplete(Result)}
 * via {@link #getContent()} or {@link #getContentAsString()}.</p>
 */
public abstract class BufferingResponseListener extends Response.Listener.Empty
{
    private final int maxLength;
    private volatile byte[] buffer = new byte[0];
    private volatile String encoding;

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
        this.maxLength = maxLength;
    }

    @Override
    public void onHeaders(Response response)
    {
        HttpFields headers = response.getHeaders();
        long length = headers.getLongField(HttpHeader.CONTENT_LENGTH.asString());
        if (length > maxLength)
            response.abort(new IllegalArgumentException("Buffering capacity exceeded"));

        String contentType = headers.get(HttpHeader.CONTENT_TYPE);
        if (contentType != null)
        {
            String charset = "charset=";
            int index = contentType.toLowerCase(Locale.ENGLISH).indexOf(charset);
            if (index > 0)
            {
                String encoding = contentType.substring(index + charset.length());
                // Sometimes charsets arrive with an ending semicolon
                index = encoding.indexOf(';');
                if (index > 0)
                    encoding = encoding.substring(0, index);
                this.encoding = encoding;
            }
        }
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        long newLength = buffer.length + content.remaining();
        if (newLength > maxLength)
            response.abort(new IllegalArgumentException("Buffering capacity exceeded"));

        byte[] newBuffer = new byte[(int)newLength];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        content.get(newBuffer, buffer.length, content.remaining());
        buffer = newBuffer;
    }

    @Override
    public abstract void onComplete(Result result);

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
        return buffer;
    }

    /**
     * @return the content as a string, using the "Content-Type" header to detect the encoding
     *         or defaulting to UTF-8 if the encoding could not be detected.
     * @see #getContentAsString(String)
     */
    public String getContentAsString()
    {
        String encoding = this.encoding;
        if (encoding == null)
            encoding = "UTF-8";
        return getContentAsString(encoding);
    }

    /**
     * @param encoding the encoding of the content bytes
     * @return the content as a string, with the specified encoding
     * @see #getContentAsString()
     */
    public String getContentAsString(String encoding)
    {
        try
        {
            return new String(getContent(), encoding);
        }
        catch (UnsupportedEncodingException x)
        {
            throw new UnsupportedCharsetException(encoding);
        }
    }
}
