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

import java.io.File;
import java.util.concurrent.Future;

import org.eclipse.jetty.client.api.Address;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.ContentDecoder;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.FutureCallback;

public class StandardRequest implements Request, Request.Builder
{
    private final HTTPClient client;
    private final Address address;
    private boolean secure;
    private String method;
    private String path;
    private String agent;
    private Response response;

    public StandardRequest(HTTPClient client, Address address)
    {
        this.client = client;
        this.address = address;
    }

    @Override
    public Request.Builder secure(boolean secure)
    {
        this.secure = secure;
        return this;
    }

    @Override
    public Request.Builder method(String method)
    {
        this.method = method;
        return this;
    }

    @Override
    public Request.Builder path(String path)
    {
        this.path = path;
        return this;
    }

    @Override
    public Request.Builder agent(String userAgent)
    {
        this.agent = userAgent;
        return this;
    }

    @Override
    public Request.Builder header(String name, String value)
    {
        return this;
    }

    @Override
    public Request.Builder listener(Request.Listener listener)
    {
        return this;
    }

    @Override
    public Request.Builder file(File file)
    {
        return this;
    }

    @Override
    public Request.Builder content(ContentProvider buffer)
    {
        return this;
    }

    @Override
    public Request.Builder decoder(ContentDecoder decoder)
    {
        return this;
    }

    @Override
    public Request.Builder param(String name, String value)
    {
        return this;
    }

    @Override
    public Request.Builder cookie(String key, String value)
    {
        return this;
    }

    @Override
    public Request.Builder authentication(Authentication authentication)
    {
        return this;
    }

    @Override
    public Request.Builder followRedirects(boolean follow)
    {
        return this;
    }

    @Override
    public Request build()
    {
        return this;
    }

    @Override
    public Future<Response> send()
    {
        return send(null);
    }

    @Override
    public Future<Response> send(final Response.Listener listener)
    {
        return client.send(this, listener);
    }
}
