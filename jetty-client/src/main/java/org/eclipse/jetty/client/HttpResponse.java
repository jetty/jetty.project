//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class HttpResponse implements Response
{
    private final HttpFields headers = new HttpFields();
    private final Request request;
    private final Listener listener;
    private HttpVersion version;
    private int status;
    private String reason;

    public HttpResponse(Request request, Response.Listener listener)
    {
        this.request = request;
        this.listener = listener;
    }

    public HttpVersion version()
    {
        return version;
    }

    public HttpResponse version(HttpVersion version)
    {
        this.version = version;
        return this;
    }

    @Override
    public int status()
    {
        return status;
    }

    public HttpResponse status(int status)
    {
        this.status = status;
        return this;
    }

    public String reason()
    {
        return reason;
    }

    public HttpResponse reason(String reason)
    {
        this.reason = reason;
        return this;
    }

    @Override
    public HttpFields headers()
    {
        return headers;
    }

    @Override
    public Request request()
    {
        return request;
    }

    @Override
    public Listener listener()
    {
        return listener;
    }

    @Override
    public void abort()
    {
//        request.abort();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s %d %s]", HttpResponse.class.getSimpleName(), version(), status(), reason());
    }
}
