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

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.client.api.Connection;

/**
 * <p>Client-side connection pool abstraction.</p>
 */
public interface ConnectionPool extends Closeable
{
    /**
     * Optionally pre-create up to {@code connectionCount}
     * connections so they are immediately ready for use.
     * @param connectionCount the number of connections to pre-start.
     */
    default CompletableFuture<Void> preCreateConnections(int connectionCount)
    {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * @param connection the connection to test
     * @return whether the given connection is currently in use
     */
    boolean isActive(Connection connection);

    /**
     * @return whether this ConnectionPool has no open connections
     */
    boolean isEmpty();

    /**
     * @return whether this ConnectionPool has been closed
     * @see #close()
     */
    boolean isClosed();

    /**
     * <p>Returns an idle connection, if available;
     * if an idle connection is not available, and the given {@code create} parameter is {@code true},
     * then schedules the opening of a new connection, if possible within the configuration of this
     * connection pool (for example, if it does not exceed the max connection count);
     * otherwise returns {@code null}.</p>
     *
     * @param create whether to schedule the opening of a connection if no idle connections are available
     * @return an idle connection or {@code null} if no idle connections are available
     */
    Connection acquire(boolean create);

    /**
     * <p>Accepts the given connection to be managed by this ConnectionPool.</p>
     *
     * @param connection the connection to accept
     * @return whether the connection has been accepted
     */
    boolean accept(Connection connection);

    /**
     * <p>Returns the given connection, previously obtained via {@link #acquire(boolean)},
     * back to this ConnectionPool.</p>
     *
     * @param connection the connection to release
     * @return true if the connection has been released, false if the connection
     * should be closed
     */
    boolean release(Connection connection);

    /**
     * <p>Removes the given connection from this ConnectionPool.</p>
     *
     * @param connection the connection to remove
     * @return true if the connection was removed from this ConnectionPool
     */
    boolean remove(Connection connection);

    @Override
    void close();

    /**
     * Factory for ConnectionPool instances.
     */
    interface Factory
    {
        /**
         * Creates a new ConnectionPool for the given destination.
         *
         * @param destination the destination to create the ConnectionPool for
         * @return the newly created ConnectionPool
         */
        ConnectionPool newConnectionPool(HttpDestination destination);
    }

    /**
     * Marks a connection as supporting multiplexed requests.
     */
    interface MaxMultiplexable
    {
        /**
         * @return the max number of requests multiplexable on a single connection
         */
        int getMaxMultiplex();
    }

    /**
     * Marks a connection as being usable for a maximum number of requests.
     */
    interface MaxUsable
    {
        /**
         * @return the max number of requests on a single connection
         */
        int getMaxUsage();
    }
}
