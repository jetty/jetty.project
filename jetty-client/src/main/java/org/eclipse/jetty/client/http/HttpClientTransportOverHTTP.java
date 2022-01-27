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

package org.eclipse.jetty.client.http;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.DuplexHttpDestination;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("The HTTP/1.1 client transport")
public class HttpClientTransportOverHTTP extends AbstractConnectorHttpClientTransport
{
    public static final Origin.Protocol HTTP11 = new Origin.Protocol(List.of("http/1.1"), false);
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientTransportOverHTTP.class);

    private final ClientConnectionFactory factory = new HttpClientConnectionFactory();
    private int headerCacheSize = 1024;
    private boolean headerCacheCaseSensitive;

    public HttpClientTransportOverHTTP()
    {
        this(Math.max(1, ProcessorUtils.availableProcessors() / 2));
    }

    public HttpClientTransportOverHTTP(int selectors)
    {
        this(new ClientConnector());
        getClientConnector().setSelectors(selectors);
    }

    public HttpClientTransportOverHTTP(ClientConnector connector)
    {
        super(connector);
        setConnectionPoolFactory(destination -> new DuplexConnectionPool(destination, getHttpClient().getMaxConnectionsPerDestination(), destination));
    }

    @Override
    public Origin newOrigin(HttpRequest request)
    {
        return getHttpClient().createOrigin(request, HTTP11);
    }

    @Override
    public HttpDestination newHttpDestination(Origin origin)
    {
        SocketAddress address = origin.getAddress().getSocketAddress();
        return new DuplexHttpDestination(getHttpClient(), origin, getClientConnector().isIntrinsicallySecure(address));
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        var connection = factory.newConnection(endPoint, context);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return connection;
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

    @ManagedAttribute("Whether the header field cache is case sensitive")
    public boolean isHeaderCacheCaseSensitive()
    {
        return headerCacheCaseSensitive;
    }

    public void setHeaderCacheCaseSensitive(boolean headerCacheCaseSensitive)
    {
        this.headerCacheCaseSensitive = headerCacheCaseSensitive;
    }
}
