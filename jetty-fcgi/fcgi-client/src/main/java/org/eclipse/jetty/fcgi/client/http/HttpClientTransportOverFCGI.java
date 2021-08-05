//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.client.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.DuplexHttpDestination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("The FastCGI/1.0 client transport")
public class HttpClientTransportOverFCGI extends AbstractConnectorHttpClientTransport
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientTransportOverFCGI.class);

    private final String scriptRoot;

    public HttpClientTransportOverFCGI(String scriptRoot)
    {
        this(Math.max(1, ProcessorUtils.availableProcessors() / 2), scriptRoot);
    }

    public HttpClientTransportOverFCGI(int selectors, String scriptRoot)
    {
        this(new ClientConnector(), scriptRoot);
        getClientConnector().setSelectors(selectors);
    }

    public HttpClientTransportOverFCGI(ClientConnector connector, String scriptRoot)
    {
        super(connector);
        this.scriptRoot = scriptRoot;
        setConnectionPoolFactory(destination ->
        {
            HttpClient httpClient = getHttpClient();
            int maxConnections = httpClient.getMaxConnectionsPerDestination();
            return new DuplexConnectionPool(destination, maxConnections, destination);
        });
    }

    @ManagedAttribute(value = "The scripts root directory", readonly = true)
    public String getScriptRoot()
    {
        return scriptRoot;
    }

    @Override
    public Origin newOrigin(HttpRequest request)
    {
        return getHttpClient().createOrigin(request, new Origin.Protocol(List.of("fastcgi/1.1"), false));
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new DuplexHttpDestination(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        org.eclipse.jetty.io.Connection connection = newHttpConnection(endPoint, destination, promise);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return customize(connection, context);
    }

    protected HttpConnectionOverFCGI newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
    {
        return new HttpConnectionOverFCGI(endPoint, destination, promise);
    }

    protected void customize(Request request, HttpFields.Mutable fastCGIHeaders)
    {
        fastCGIHeaders.put(FCGI.Headers.DOCUMENT_ROOT, getScriptRoot());
    }
}
