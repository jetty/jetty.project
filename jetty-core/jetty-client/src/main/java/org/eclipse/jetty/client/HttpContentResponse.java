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

package org.eclipse.jetty.client;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class HttpContentResponse implements ContentResponse
{
    private final Response response;
    private final byte[] content;
    private final String mediaType;
    private final String encoding;

    public HttpContentResponse(Response response, byte[] content, String mediaType, String encoding)
    {
        this.response = response;
        this.content = content;
        this.mediaType = mediaType;
        this.encoding = encoding;
    }

    @Override
    public Request getRequest()
    {
        return response.getRequest();
    }

    @Override
    public <T extends ResponseListener> List<T> getListeners(Class<T> listenerClass)
    {
        return response.getListeners(listenerClass);
    }

    @Override
    public HttpVersion getVersion()
    {
        return response.getVersion();
    }

    @Override
    public int getStatus()
    {
        return response.getStatus();
    }

    @Override
    public String getReason()
    {
        return response.getReason();
    }

    @Override
    public HttpFields getHeaders()
    {
        return response.getHeaders();
    }

    @Override
    public HttpFields getTrailers()
    {
        return response.getTrailers();
    }

    @Override
    public void abort(Throwable cause)
    {
        response.abort(cause);
    }

    @Override
    public String getMediaType()
    {
        return mediaType;
    }

    @Override
    public String getEncoding()
    {
        return encoding;
    }

    @Override
    public byte[] getContent()
    {
        return content;
    }

    @Override
    public String getContentAsString()
    {
        String encoding = this.encoding;
        if (encoding == null)
        {
            return new String(getContent(), StandardCharsets.UTF_8);
        }
        else
        {
            try
            {
                return new String(getContent(), encoding);
            }
            catch (UnsupportedEncodingException e)
            {
                throw new UnsupportedCharsetException(encoding);
            }
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %d %s - %d bytes]",
            HttpContentResponse.class.getSimpleName(),
            getVersion(),
            getStatus(),
            getReason(),
            getContent().length);
    }
}
