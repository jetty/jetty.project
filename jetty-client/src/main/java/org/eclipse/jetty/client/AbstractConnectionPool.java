//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class AbstractConnectionPool implements ConnectionPool, Dumpable
{
    private static final Logger LOG = Log.getLogger(AbstractConnectionPool.class);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger connectionCount = new AtomicInteger();
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
        return connectionCount.get();
    }

    @Override
    public boolean isEmpty()
    {
        return connectionCount.get() == 0;
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
            connection = tryCreate();
        return connection;
    }

    private Connection tryCreate()
    {
        while (true)
        {
            int current = getConnectionCount();
            final int next = current + 1;

            if (next > maxConnections)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Max connections {}/{} reached", current, maxConnections);
                // Try again the idle connections
                return activate();
            }

            if (connectionCount.compareAndSet(current, next))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Connection {}/{} creation", next, maxConnections);

                destination.newConnection(new Promise<Connection>()
                {
                    @Override
                    public void succeeded(Connection connection)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection {}/{} creation succeeded {}", next, maxConnections, connection);
                        onCreated(connection);
                        proceed();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Connection " + next + "/" + maxConnections + " creation failed", x);
                        connectionCount.decrementAndGet();
                        requester.failed(x);
                    }
                });

                // Try again the idle connections
                return activate();
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
        int pooled = connectionCount.decrementAndGet();
        if (LOG.isDebugEnabled())
            LOG.debug("Connection removed {} - pooled: {}", connection, pooled);
    }

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            connectionCount.set(0);
        }
    }

    protected void close(Collection<Connection> connections)
    {
        connections.forEach(Connection::close);
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }
}
