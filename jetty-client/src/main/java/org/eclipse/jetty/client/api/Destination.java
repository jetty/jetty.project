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

package org.eclipse.jetty.client.api;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;

/**
 * {@link Destination} represents the triple made of the {@link #getScheme}, the {@link #getHost}
 * and the {@link #getPort}.
 * <p>
 * {@link Destination} holds a pool of {@link Connection}s, but allows to create unpooled
 * connections if the application wants full control over connection management via {@link #newConnection(Promise)}.
 * <p>
 * {@link Destination}s may be obtained via {@link HttpClient#resolveDestination(Request)}
 */
public interface Destination
{
    /**
     * @return the scheme of this destination, such as "http" or "https"
     */
    String getScheme();

    /**
     * @return the host of this destination, such as "127.0.0.1" or "google.com"
     */
    String getHost();

    /**
     * @return the port of this destination such as 80 or 443
     */
    int getPort();

    /**
     * Creates asynchronously a new, unpooled, {@link Connection} that will be returned
     * at a later time through the given {@link Promise}.
     * <p>
     * Use {@link FuturePromise} to wait for the connection:
     * <pre>
     * Destination destination = ...;
     * FuturePromise&lt;Connection&gt; futureConnection = new FuturePromise&lt;&gt;();
     * destination.newConnection(futureConnection);
     * Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
     * </pre>
     *
     * @param promise the promise of a new, unpooled, {@link Connection}
     */
    void newConnection(Promise<Connection> promise);
}
