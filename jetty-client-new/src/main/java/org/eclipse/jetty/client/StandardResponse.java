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

import java.io.InputStream;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Headers;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.FutureCallback;

public class StandardResponse extends FutureCallback<Response> implements Response
{
    private final Request request;
    private final Listener listener;

    public StandardResponse(Request request, Response.Listener listener)
    {
        this.request = request;
        this.listener = listener;
    }

    @Override
    public int getStatus()
    {
        return 0;
    }

    @Override
    public Headers getHeaders()
    {
        return null;
    }

    @Override
    public Request getRequest()
    {
        return request;
    }

    @Override
    public ContentProvider content()
    {
        return null;
    }

    @Override
    public InputStream contentAsStream()
    {
        return null;
    }

    @Override
    public void abort()
    {
        request.abort();
    }
}
