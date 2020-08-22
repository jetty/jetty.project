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
import org.eclipse.jetty.util.thread.Locker;

@ManagedObject
public class RoundRobinConnectionPool extends MultiplexConnectionPool
{
    private static final Logger LOG = Log.getLogger(RoundRobinConnectionPool.class);

    private final Locker lock = new Locker();
    private final Pool<Connection> pool;
    private int offset;

    public RoundRobinConnectionPool(HttpDestination destination, int maxConnections, Callback requester)
    {
        this(destination, maxConnections, requester, 1);
    }

    public RoundRobinConnectionPool(HttpDestination destination, int maxConnections, Callback requester, int maxMultiplex)
    {
        super(destination, maxConnections, false, requester, maxMultiplex);
        pool = destination.getBean(Pool.class);
    }

    @Override
    protected Connection acquire(boolean create)
    {
        // If there are queued requests and connections get
        // closed due to idle timeout or overuse, we want to
        // aggressively try to open new connections to replace
        // those that were closed to process queued requests.
        return super.acquire(true);
    }

    @Override
    protected Connection activate()
    {
        Pool<Connection>.Entry entry;
        try (Locker.Lock l = lock.lock())
        {
            int index = Math.abs(offset % pool.getMaxEntries());
            entry = pool.acquireAt(index);
            if (LOG.isDebugEnabled())
                LOG.debug("activated at index={} entry={}", index, entry);
            if (entry != null)
                ++offset;
        }
        if (entry == null)
            return null;
        Connection connection = entry.getPooled();
        acquired(connection);
        return connection;
    }
}
