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

import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link Destination} represents the receiver of HTTP requests, and it is
 * identified by an {@link Origin}.</p>
 * <p>{@link Destination} holds a pool of {@link Connection}s, but allows to create unpooled
 * connections if the application wants full control over connection management via
 * {@link #newConnection(Promise)}.</p>
 * <p>{@link Destination}s may be obtained via {@link HttpClient#resolveDestination(Request)}.</p>
 */
public interface Destination
{
    /**
     * @return the origin of this destination
     */
    Origin getOrigin();

    /**
     * @return whether the communication with the destination is secure
     */
    boolean isSecure();

    /**
     * @return the proxy associated with this destination,
     * or {@code null} if there is no proxy
     */
    ProxyConfiguration.Proxy getProxy();

    /**
     * @return the connection pool associated with this destination
     */
    ConnectionPool getConnectionPool();

    /**
     * @return the {@code HttpClient} that manages this destination
     */
    HttpClient getHttpClient();

    /**
     * Creates asynchronously a new, unpooled, {@link Connection} that will be returned
     * at a later time through the given {@link Promise}.
     * <p>
     * Use {@link FuturePromise} to wait for the connection:
     * <pre>{@code
     * Destination destination = ...;
     * FuturePromise<Connection> futureConnection = new FuturePromise<>();
     * destination.newConnection(futureConnection);
     * Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
     * }</pre>
     *
     * @param promise the promise of a new, unpooled, {@link Connection}
     */
    void newConnection(Promise<Connection> promise);

    /**
     * <p>Sends the given request to this destination.</p>
     * <p>You can use this method to send the request to a specific
     * destination that may be different from the request authority.</p>
     * <p>For example when {@link HttpClient} is used in a proxy, it may
     * receive a request with authority {@code yourserver.com} but the
     * proxy logic may want to forward the request to a specific backend
     * server, say {@code backend01}, therefore:</p>
     * <pre>{@code
     * // Resolve the backend destination.
     * Origin backendOrigin = new Origin(backendScheme, "backend01", backendPort);
     * Destination backendDestination = httpClient.resolveDestination(backendOrigin);
     *
     * // Create a request with the original authority.
     * Request request = httpClient.newRequest("https://yourserver.com/path");
     *
     * // Send the request to the specific backend.
     * backendDestination.send(request, result -> { ... });
     * }</pre>
     *
     * @param request the request to send to this destination
     * @param listener the listener that receives response events
     */
    void send(Request request, Response.CompleteListener listener);
}
