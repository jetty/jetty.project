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

package org.eclipse.jetty.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;

import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.io.ClientConnectionFactory;

/**
 * {@link HttpClientTransport} represents what transport implementations should provide
 * in order to plug-in a different transport for {@link HttpClient}.
 * <p>
 * While the {@link HttpClient} APIs define the HTTP semantic (request, response, headers, etc.)
 * <em>how</em> an HTTP exchange is carried over the network depends on implementations of this class.
 * <p>
 * The default implementation uses the HTTP protocol to carry over the network the HTTP exchange,
 * but the HTTP exchange may also be carried using the FCGI protocol, the HTTP/2 protocol or,
 * in future, other protocols.
 */
public interface HttpClientTransport extends ClientConnectionFactory
{
    public static final String HTTP_DESTINATION_CONTEXT_KEY = "org.eclipse.jetty.client.destination";
    public static final String HTTP_CONNECTION_PROMISE_CONTEXT_KEY = "org.eclipse.jetty.client.connection.promise";

    /**
     * Sets the {@link HttpClient} instance on this transport.
     * <p>
     * This is needed because of a chicken-egg problem: in order to create the {@link HttpClient}
     * a HttpClientTransport is needed, that therefore cannot have a reference yet to the
     * {@link HttpClient}.
     *
     * @param client the {@link HttpClient} that uses this transport.
     */
    public void setHttpClient(HttpClient client);

    /**
     * Creates a new Origin with the given request.
     *
     * @param request the request that triggers the creation of the Origin
     * @return an Origin that identifies a destination
     */
    public Origin newOrigin(Request request);

    /**
     * Creates a new, transport-specific, {@link HttpDestination} object.
     * <p>
     * {@link HttpDestination} controls the destination-connection cardinality: protocols like
     * HTTP have 1-N cardinality, while multiplexed protocols like HTTP/2 have a 1-1 cardinality.
     *
     * @param origin the destination origin
     * @return a new, transport-specific, {@link HttpDestination} object
     */
    public Destination newDestination(Origin origin);

    /**
     * Establishes a physical connection to the given {@code address}.
     *
     * @param address the address to connect to
     * @param context the context information to establish the connection
     * @deprecated use {@link #connect(SocketAddress, Map)} instead.
     */
    @Deprecated
    public void connect(InetSocketAddress address, Map<String, Object> context);

    /**
     * Establishes a physical connection to the given {@code address}.
     *
     * @param address the address to connect to
     * @param context the context information to establish the connection
     */
    public default void connect(SocketAddress address, Map<String, Object> context)
    {
        if (address instanceof InetSocketAddress)
            connect((InetSocketAddress)address, context);
        else
            throw new UnsupportedOperationException("Unsupported SocketAddress " + address);
    }

    /**
     * @return the factory for ConnectionPool instances
     */
    public ConnectionPool.Factory getConnectionPoolFactory();

    /**
     * @param factory the factory for ConnectionPool instances
     */
    public void setConnectionPoolFactory(ConnectionPool.Factory factory);
}
