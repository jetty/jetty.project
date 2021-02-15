//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.client.http;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject("The HTTP/1.1 client transport")
public class HttpClientTransportOverHTTP extends AbstractConnectorHttpClientTransport
{
    private int headerCacheSize = 1024;

    public HttpClientTransportOverHTTP()
    {
        this(Math.max(1, ProcessorUtils.availableProcessors() / 2));
    }

    public HttpClientTransportOverHTTP(int selectors)
    {
        super(selectors);
        setConnectionPoolFactory(destination -> new DuplexConnectionPool(destination, getHttpClient().getMaxConnectionsPerDestination(), destination));
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        return new HttpDestinationOverHTTP(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        HttpConnectionOverHTTP connection = newHttpConnection(endPoint, destination, promise);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return customize(connection, context);
    }

    protected HttpConnectionOverHTTP newHttpConnection(EndPoint endPoint, HttpDestination destination, Promise<Connection> promise)
    {
        return new HttpConnectionOverHTTP(endPoint, destination, promise);
    }

    @ManagedAttribute("The maximum allowed size in bytes for an HTTP header field cache")
    public int getHeaderCacheSize()
    {
        return headerCacheSize;
    }

    public void setHeaderCacheSize(int headerCacheSize)
    {
        this.headerCacheSize = headerCacheSize;
    }
}
