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

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.AtomicBiInteger;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public abstract class AbstractConnectionPool implements ConnectionPool, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractConnectionPool.class);

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * The connectionCount encodes both the total connections plus the pending connection counts, so both can be atomically changed.
     * The bottom 32 bits represent the total connections and the top 32 bits represent the pending connections.
     */
    private final AtomicBiInteger connections = new AtomicBiInteger();
    private final Destination destination;
    private final int maxConnections;
    private final Callback requester;

    protected AbstractConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        this.destination = destination;
        this.maxConnections = maxConnections;
        this.requester = requester;
    }

    @ManagedAttribute(value = "The max number of connections", readonly = true)
    public int getMaxConnectionCount()
    {
        return maxConnections;
    }

    @ManagedAttribute(value = "The number of connections", readonly = true)
    public int getConnectionCount()
    {
        return connections.getLo();
    }

    @ManagedAttribute(value = "The number of pending connections", readonly = true)
    public int getPendingConnectionCount()
    {
        return connections.getHi();
    }

    @Override
    public boolean isEmpty()
    {
        return connections.getLo() == 0;
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public Connection acquire()
    {
        Connection connection = activate();
        if (connection == null)
        {
            tryCreate(-1);
            connection = activate();
        }
        return connection;
    }

    protected void tryCreate(int maxPending)
    {
        while (true)
        {
            long encoded = connections.get();
            int pending = AtomicBiInteger.getHi(encoded);
            int total = AtomicBiInteger.getLo(encoded);

            if (LOG.isDebugEnabled())
                LOG.debug("tryCreate {}/{} connections {}/{} pending", total, maxConnections, pending, maxPending);

            if (total >= maxConnections)
                return;

            if (maxPending >= 0 && pending >= maxPending)
                return;

            if (connections.compareAndSet(encoded, pending + 1, total + 1))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("newConnection {}/{} connections {}/{} pending", total + 1, maxConnections, pending + 1, maxPending);

                destination.newConnection(new Promise<>()
                {
                    @Override
                    public void succeeded(Connection connection)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection {}/{} creation succeeded {}", total + 1, maxConnections, connection);
                        connections.add(-1, 0);
                        onCreated(connection);
                        proceed();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection " + (total + 1) + "/" + maxConnections + " creation failed", x);
                        connections.add(-1, -1);
                        requester.failed(x);
                    }
                });

                return;
            }
        }
    }

    protected abstract void onCreated(Connection connection);

    protected void proceed()
    {
        requester.succeeded();
    }

    protected abstract Connection activate();

    protected Connection active(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Connection active {}", connection);
        acquired(connection);
        return connection;
    }

    protected void acquired(Connection connection)
    {
    }

    protected boolean idle(Connection connection, boolean close)
    {
        if (close)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection idle close {}", connection);
            return false;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection idle {}", connection);
            return true;
        }
    }

    protected void released(Connection connection)
    {
    }

    protected void removed(Connection connection)
    {
        int pooled = connections.addAndGetLo(-1);
        if (LOG.isDebugEnabled())
            LOG.debug("Connection removed {} - pooled: {}", connection, pooled);
    }

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            connections.set(0, 0);
        }
    }

    protected void close(Collection<Connection> connections)
    {
        connections.forEach(Connection::close);
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }
}
