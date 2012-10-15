//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.UnsupportedEncodingException;
import java.nio.charset.UnsupportedCharsetException;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class HttpContentResponse implements ContentResponse
{
    private final Response response;
    private final byte[] content;
    private final String encoding;

    public HttpContentResponse(Response response, byte[] content, String encoding)
    {
        this.response = response;
        this.content = content;
        this.encoding = encoding;
    }

    @Override
    public long conversation()
    {
        return response.conversation();
    }

    @Override
    public Listener listener()
    {
        return response.listener();
    }

    @Override
    public HttpVersion version()
    {
        return response.version();
    }

    @Override
    public int status()
    {
        return response.status();
    }

    @Override
    public String reason()
    {
        return response.reason();
    }

    @Override
    public HttpFields headers()
    {
        return response.headers();
    }

    @Override
    public void abort()
    {
        response.abort();
    }

    @Override
    public byte[] content()
    {
        return content;
    }

    @Override
    public String contentAsString()
    {
        String encoding = this.encoding;
        try
        {
            return new String(content(), encoding == null ? "UTF-8" : encoding);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new UnsupportedCharsetException(encoding);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %d %s - %d bytes]",
                HttpContentResponse.class.getSimpleName(),
                version(),
                status(),
                reason(),
                content().length);
    }
}
