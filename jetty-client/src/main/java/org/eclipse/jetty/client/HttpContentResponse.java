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

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class HttpContentResponse implements ContentResponse
{
    private final Response response;
    private final BufferingResponseListener listener;

    public HttpContentResponse(Response response, BufferingResponseListener listener)
    {
        this.response = response;
        this.listener = listener;
    }

    @Override
    public Request request()
    {
        return response.request();
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
        return listener.content();
    }
}
