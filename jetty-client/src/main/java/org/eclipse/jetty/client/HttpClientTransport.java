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

package org.eclipse.jetty.client;

import java.net.SocketAddress;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Promise;

/**
 * {@link HttpClientTransport} represents what transport implementations should provide
 * in order to plug-in a different transport for {@link HttpClient}.
 * <p/>
 * While the {@link HttpClient} APIs define the HTTP semantic (request, response, headers, etc.)
 * <em>how</em> a HTTP exchange is carried over the network depends on implementations of this class.
 * <p/>
 * The default implementation uses the HTTP protocol to carry over the network the HTTP exchange,
 * but the HTTP exchange may also be carried using the SPDY protocol or the FCGI protocol or, in future,
 * other protocols.
 */
public interface HttpClientTransport
{
    /**
     * Sets the {@link HttpClient} instance on this transport.
     * <p />
     * This is needed because of a chicken-egg problem: in order to create the {@link HttpClient}
     * a {@link HttpClientTransport} is needed, that therefore cannot have a reference yet to the
     * {@link HttpClient}.
     *
     * @param client the {@link HttpClient} that uses this transport.
     */
    public void setHttpClient(HttpClient client);

    /**
     * Creates a new, transport-specific, {@link HttpDestination} object.
     * <p />
     * {@link HttpDestination} controls the destination-connection cardinality: protocols like
     * HTTP have 1-N cardinality, while multiplexed protocols like SPDY have a 1-1 cardinality.
     *
     * @param scheme the destination scheme
     * @param host the destination host
     * @param port the destination port
     * @return a new, transport-specific, {@link HttpDestination} object
     */
    public HttpDestination newHttpDestination(String scheme, String host, int port);

    /**
     * Establishes a physical connection to the given {@code address}.
     *
     * @param destination the destination
     * @param address the address to connect to
     * @param promise the promise to notify when the connection succeeds or fails
     */
    public void connect(HttpDestination destination, SocketAddress address, Promise<Connection> promise);

    /**
     * Establishes an encrypted tunnel over the given {@code connection}
     *
     * @param connection the connection to tunnel
     * @return the tunnelled connection
     */
    public Connection tunnel(Connection connection);
}
