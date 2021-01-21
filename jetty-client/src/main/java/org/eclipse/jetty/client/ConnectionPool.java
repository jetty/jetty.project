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
     * Optionally pre-create up to <code>connectionCount</code>
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
     * <p>Returns an idle connection, if available, or schedules the opening
     * of a new connection and returns {@code null}.</p>
     *
     * @return an available connection, or null
     */
    Connection acquire();

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

    /**
     * Closes this ConnectionPool.
     *
     * @see #isClosed()
     */
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
