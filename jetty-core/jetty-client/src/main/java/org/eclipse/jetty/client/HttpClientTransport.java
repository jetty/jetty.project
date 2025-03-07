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

package org.eclipse.jetty.client;

import java.net.SocketAddress;
import java.util.Map;

import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * {@link HttpClientTransport} represents what transport implementations should provide
 * in order to plug in a different transport for {@link HttpClient}.
 * <p>
 * While the {@link HttpClient} APIs define the HTTP semantic (request, response, headers, etc.)
 * <em>how</em> an HTTP exchange is carried over the network depends on implementations of this class.
 * <p>
 * The default implementation uses the HTTP protocol to carry over the network the HTTP exchange,
 * but the HTTP exchange may also be carried using the FCGI protocol, the HTTP/2 protocol or,
 * in the future, other protocols.
 */
public interface HttpClientTransport extends ClientConnectionFactory, HttpClient.Aware, Invocable
{
    String HTTP_DESTINATION_CONTEXT_KEY = "org.eclipse.jetty.client.destination";
    String HTTP_CONNECTION_PROMISE_CONTEXT_KEY = "org.eclipse.jetty.client.connection.promise";

    /**
     * Sets the {@link HttpClient} instance on this transport.
     * <p>
     * This is needed because of a chicken-egg problem: in order to create the {@link HttpClient}
     * a HttpClientTransport is needed, that therefore cannot have a reference yet to the
     * {@link HttpClient}.
     *
     * @param client the {@link HttpClient} that uses this transport.
     */
    @Override
    void setHttpClient(HttpClient client);

    /**
     * Creates a new Origin with the given request.
     *
     * @param request the request that triggers the creation of the Origin
     * @return an Origin that identifies a destination
     */
    Origin newOrigin(Request request);

    /**
     * Creates a new, transport-specific, {@link HttpDestination} object.
     * <p>
     * {@link HttpDestination} controls the destination-connection cardinality: protocols like
     * HTTP have 1-N cardinality, while multiplexed protocols like HTTP/2 have a 1-1 cardinality.
     *
     * @param origin the destination origin
     * @return a new, transport-specific, {@link HttpDestination} object
     */
    Destination newDestination(Origin origin);

    /**
     * Establishes a physical connection to the given {@code address}.
     *
     * @param address the address to connect to
     * @param context the context information to establish the connection
     */
    void connect(SocketAddress address, Map<String, Object> context);

    /**
     * @return the factory for ConnectionPool instances
     */
    ConnectionPool.Factory getConnectionPoolFactory();

    /**
     * Set the factory for ConnectionPool instances.
     * @param factory the factory for ConnectionPool instances
     */
    void setConnectionPoolFactory(ConnectionPool.Factory factory);

    /**
     * @return the {@link InvocationType} associated with this {@code HttpClientTransport}.
     * @see #setInvocationType(InvocationType)
     */
    @Override
    InvocationType getInvocationType();

    /**
     * <p>Sets the {@link InvocationType} associated with this {@code HttpClientTransport}.</p>
     * <p>The values are typically either:
     * <ul>
     *   <li>{@link InvocationType#BLOCKING}, to indicate that response listeners are
     *   executing blocking code, for example blocking network I/O, JDBC, etc.</li>
     *   <li>{@link InvocationType#NON_BLOCKING}, to indicate that response listeners
     *   are executing non-blocking code.</li>
     * </ul>
     * <p>By default, the value is {@link InvocationType#BLOCKING}.</p>
     * <p>A response listener declared to be {@link InvocationType#BLOCKING} incurs
     * in one additional context switch, where the NIO processing thread delegates
     * the response processing to another thread.
     * This ensures that the NIO processing thread can immediately continue with
     * other NIO processing activities, if any (for example, processing another
     * connection).
     * This also means that processing of different connections is parallelized.</p>
     * <p>{@link InvocationType#BLOCKING} must be used when you want response
     * listeners to be invoked by virtual threads.</p>
     * <p>On the other hand, a response listener declared to be
     * {@link InvocationType#NON_BLOCKING} does not incur in the additional
     * context switch, and therefore it is potentially more efficient.
     * However, the processing of different connections is serialized, which
     * means that the last connection will be processed only after the previous
     * connections (and their respective response listeners) have been processed.</p>
     * <p>A response listener declared to be {@link InvocationType#NON_BLOCKING},
     * but then executing blocking code, will block the NIO processing performed
     * by {@link HttpClient}'s implementation: the current connection and possibly
     * other connections will not be further processed, until the blocking response
     * listener returns.</p>
     *
     * @param invocationType the {@link InvocationType} associated with this {@code HttpClientTransport}.
     * @see #getInvocationType()
     */
    void setInvocationType(InvocationType invocationType);
}
