//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link MultiplexConnectionPool} that picks connections at a particular
 * index between {@code 0} and {@link #getMaxConnectionCount()}.</p>
 * <p>The algorithm that decides the index value is decided by subclasses.</p>
 * <p>To acquire a connection, this class obtains the index value and attempts
 * to activate the pool entry at that index.
 * If this activation fails, another attempt to activate an alternative pool
 * entry is performed, to avoid stalling connection acquisition if there is
 * an available entry at a different index.</p>
 */
@ManagedObject
public abstract class IndexedConnectionPool extends MultiplexConnectionPool
{
    private static final Logger LOG = Log.getLogger(IndexedConnectionPool.class);

    private final Pool<Connection> pool;

    public IndexedConnectionPool(HttpDestination destination, int maxConnections, Callback requester, int maxMultiplex)
    {
        super(destination, maxConnections, false, requester, maxMultiplex);
        pool = destination.getBean(Pool.class);
    }

    /**
     * <p>Must return an index between 0 (inclusive) and {@code maxConnections} (exclusive)
     * used to attempt to acquire the connection at that index in the pool.</p>
     *
     * @param maxConnections the upper bound of the index (exclusive)
     * @return an index between 0 (inclusive) and {@code maxConnections} (exclusive)
     */
    protected abstract int getIndex(int maxConnections);

    @Override
    protected Connection activate()
    {
        int index = getIndex(getMaxConnectionCount());
        Pool<Connection>.Entry entry = pool.acquireAt(index);
        if (LOG.isDebugEnabled())
            LOG.debug("activating at index={} entry={}", index, entry);
        if (entry == null)
        {
            entry = pool.acquire();
            if (LOG.isDebugEnabled())
                LOG.debug("activating alternative entry={}", entry);
        }
        if (entry == null)
            return null;
        Connection connection = entry.getPooled();
        acquired(connection);
        return connection;
    }
}
