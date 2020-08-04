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

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public class RoundRobinConnectionPool extends MultiplexConnectionPool
{
    private static final Logger LOG = LoggerFactory.getLogger(RoundRobinConnectionPool.class);

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
