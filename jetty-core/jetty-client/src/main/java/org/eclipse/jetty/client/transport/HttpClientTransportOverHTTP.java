//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.transport;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.AbstractConnectorHttpClientTransport;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("The HTTP/1.1 client transport")
public class HttpClientTransportOverHTTP extends AbstractConnectorHttpClientTransport
{
    public static final Origin.Protocol HTTP11 = new Origin.Protocol(List.of("http/1.1"), false);
    private static final Logger LOG = LoggerFactory.getLogger(HttpClientTransportOverHTTP.class);

    private final HttpClientConnectionFactory factory = new HttpClientConnectionFactory();
    private int headerCacheSize = 1024;
    private boolean headerCacheCaseSensitive;

    public HttpClientTransportOverHTTP()
    {
        this(1);
    }

    public HttpClientTransportOverHTTP(int selectors)
    {
        this(new ClientConnector());
        getClientConnector().setSelectors(selectors);
    }

    public HttpClientTransportOverHTTP(ClientConnector connector)
    {
        super(connector);
        setConnectionPoolFactory(destination -> new DuplexConnectionPool(destination, getHttpClient().getMaxConnectionsPerDestination()));
    }

    @Override
    public Origin newOrigin(Request request)
    {
        return getHttpClient().createOrigin(request, HTTP11);
    }

    @Override
    public Destination newDestination(Origin origin)
    {
        return new HttpDestination(getHttpClient(), origin);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        var connection = factory.newConnection(endPoint, context);
        if (LOG.isDebugEnabled())
            LOG.debug("Created {}", connection);
        return connection;
    }

    /**
     * @return the max size in bytes for the HTTP header field cache
     */
    @ManagedAttribute("The maximum allowed size in bytes for the HTTP header field cache")
    public int getHeaderCacheSize()
    {
        return headerCacheSize;
    }

    /**
     * @param headerCacheSize the max size in bytes for the HTTP header field cache
     */
    public void setHeaderCacheSize(int headerCacheSize)
    {
        this.headerCacheSize = headerCacheSize;
    }

    /**
     * @return whether the HTTP header field cache is case-sensitive
     */
    @ManagedAttribute("Whether the HTTP header field cache is case-sensitive")
    public boolean isHeaderCacheCaseSensitive()
    {
        return headerCacheCaseSensitive;
    }

    /**
     * @param headerCacheCaseSensitive whether the HTTP header field cache is case-sensitive
     */
    public void setHeaderCacheCaseSensitive(boolean headerCacheCaseSensitive)
    {
        this.headerCacheCaseSensitive = headerCacheCaseSensitive;
    }

    /**
     * @return whether newly created connections should be initialized with an {@code OPTIONS * HTTP/1.1} request
     */
    @ManagedAttribute("Whether newly created connections should be initialized with an OPTIONS * HTTP/1.1 request")
    public boolean isInitializeConnections()
    {
        return factory.isInitializeConnections();
    }

    /**
     * @param initialize whether newly created connections should be initialized with an {@code OPTIONS * HTTP/1.1} request
     */
    public void setInitializeConnections(boolean initialize)
    {
        factory.setInitializeConnections(initialize);
    }
}
