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

package org.eclipse.jetty.fcgi.client.http;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.client.AbstractHttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Promise;

// TODO: add parameter to tell whether use multiplex destinations or not
public class HttpClientTransportOverFCGI extends AbstractHttpClientTransport
{
    private final String scriptRoot;

    public HttpClientTransportOverFCGI(String scriptRoot)
    {
        this(Runtime.getRuntime().availableProcessors() / 2 + 1, scriptRoot);
    }

    public HttpClientTransportOverFCGI(int selectors, String scriptRoot)
    {
        super(selectors);
        this.scriptRoot = scriptRoot;
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new MultiplexHttpDestinationOverFCGI(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        HttpConnectionOverFCGI connection = new HttpConnectionOverFCGI(endPoint, destination);
        LOG.debug("Created {}", connection);
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        promise.succeeded(connection);
        return connection;
    }

    protected void customize(Request request, HttpFields fastCGIHeaders)
    {
        fastCGIHeaders.put(FCGI.Headers.DOCUMENT_ROOT, scriptRoot);
    }
}
