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

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ManagedObject
public class RoundRobinConnectionPool extends MultiplexConnectionPool
{
    private static final Logger LOG = Log.getLogger(RoundRobinConnectionPool.class);

    private final AtomicInteger offset = new AtomicInteger();
    private final Pool<Connection> pool;

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
    protected Connection activate()
    {
        int offset = this.offset.get();
        Connection connection = activate(offset);
        if (connection != null)
            this.offset.getAndIncrement();
        return connection;
    }

    private Connection activate(int offset)
    {
        Pool<Connection>.Entry entry = pool.acquireAt(Math.abs(offset % pool.getMaxEntries()));
        if (LOG.isDebugEnabled())
            LOG.debug("activated '{}'", entry);
        if (entry != null)
        {
            Connection connection = entry.getPooled();
            acquired(connection);
            return connection;
        }
        return null;
    }
}
