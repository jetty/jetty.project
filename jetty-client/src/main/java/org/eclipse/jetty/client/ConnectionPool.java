//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.io.Closeable;

import org.eclipse.jetty.client.api.Connection;

/**
 * <p>Client-side connection pool abstraction.</p>
 */
public interface ConnectionPool extends Closeable
{
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
     * <p>Returns an idle connection, if available, or schedules the opening
     * of a new connection and returns {@code null}.</p>
     *
     * @return an available connection, or null
     */
    Connection acquire();

    /**
     * <p>Accepts the given connection to be managed by this ConnectionPool.</p>
     *
     * @param connection the connection to accept
     * @return whether the connection has been accepted
     */
    boolean accept(Connection connection);

    /**
     * <p>Returns the given connection, previously obtained via {@link #acquire()},
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
     * Marks a connection pool as supporting multiplexed connections.
     */
    interface Multiplexable
    {
        /**
         * @return the max number of requests multiplexable on a single connection
         */
        int getMaxMultiplex();

        /**
         * @param maxMultiplex the max number of requests multiplexable on a single connection
         */
        void setMaxMultiplex(int maxMultiplex);
    }
}
