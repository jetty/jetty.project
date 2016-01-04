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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

@ManagedObject("The connection pool")
public class DuplexConnectionPool implements Closeable, Dumpable, Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(DuplexConnectionPool.class);

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Destination destination;
    private final int maxConnections;
    private final Callback requester;
    private final Deque<Connection> idleConnections;
    private final Queue<Connection> activeConnections;

    public DuplexConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        this.destination = destination;
        this.maxConnections = maxConnections;
        this.requester = requester;
        this.idleConnections = new LinkedBlockingDeque<>(maxConnections);
        this.activeConnections = new BlockingArrayQueue<>(maxConnections);
    }

    @ManagedAttribute(value = "The number of connections", readonly = true)
    public int getConnectionCount()
    {
        return connectionCount.get();
    }

    @ManagedAttribute(value = "The number of idle connections", readonly = true)
    public int getIdleConnectionCount()
    {
        lock();
        try
        {
            return idleConnections.size();
        }
        finally
        {
            unlock();
        }
    }

    @ManagedAttribute(value = "The number of active connections", readonly = true)
    public int getActiveConnectionCount()
    {
        lock();
        try
        {
            return activeConnections.size();
        }
        finally
        {
            unlock();
        }
    }

    public Queue<Connection> getIdleConnections()
    {
        return idleConnections;
    }

    public Queue<Connection> getActiveConnections()
    {
        return activeConnections;
    }

    public Connection acquire()
    {
        Connection connection = activateIdle();
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
                return activateIdle();
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

                        idleCreated(connection);

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
                return activateIdle();
            }
        }
    }

    protected void proceed()
    {
        requester.succeeded();
    }

    protected void idleCreated(Connection connection)
    {
        boolean idle;
        lock();
        try
        {
            // Use "cold" new connections as last.
            idle = idleConnections.offerLast(connection);
        }
        finally
        {
            unlock();
        }

        idle(connection, idle);
    }

    private Connection activateIdle()
    {
        boolean acquired;
        Connection connection;
        lock();
        try
        {
            connection = idleConnections.pollFirst();
            if (connection == null)
                return null;
            acquired = activeConnections.offer(connection);
        }
        finally
        {
            unlock();
        }

        if (acquired)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection active {}", connection);
            acquired(connection);
            return connection;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection active overflow {}", connection);
            connection.close();
            return null;
        }
    }

    protected void acquired(Connection connection)
    {
    }

    public boolean release(Connection connection)
    {
        boolean idle;
        lock();
        try
        {
            if (!activeConnections.remove(connection))
                return false;
            // Make sure we use "hot" connections first.
            idle = offerIdle(connection);
        }
        finally
        {
            unlock();
        }

        released(connection);
        return idle(connection, idle);
    }

    protected boolean offerIdle(Connection connection)
    {
        return idleConnections.offerFirst(connection);
    }

    protected boolean idle(Connection connection, boolean idle)
    {
        if (idle)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection idle {}", connection);
            return true;
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Connection idle overflow {}", connection);
            connection.close();
            return false;
        }
    }

    protected void released(Connection connection)
    {
    }

    public boolean remove(Connection connection)
    {
        return remove(connection, false);
    }

    protected boolean remove(Connection connection, boolean force)
    {
        boolean activeRemoved;
        boolean idleRemoved;
        lock();
        try
        {
            activeRemoved = activeConnections.remove(connection);
            idleRemoved = idleConnections.remove(connection);
        }
        finally
        {
            unlock();
        }

        if (activeRemoved || force)
            released(connection);
        boolean removed = activeRemoved || idleRemoved || force;
        if (removed)
        {
            int pooled = connectionCount.decrementAndGet();
            if (LOG.isDebugEnabled())
                LOG.debug("Connection removed {} - pooled: {}", connection, pooled);
        }
        return removed;
    }

    public boolean isActive(Connection connection)
    {
        lock();
        try
        {
            return activeConnections.contains(connection);
        }
        finally
        {
            unlock();
        }
    }

    public boolean isIdle(Connection connection)
    {
        lock();
        try
        {
            return idleConnections.contains(connection);
        }
        finally
        {
            unlock();
        }
    }

    public boolean isEmpty()
    {
        return connectionCount.get() == 0;
    }

    public void close()
    {
        List<Connection> idles = new ArrayList<>();
        List<Connection> actives = new ArrayList<>();
        lock();
        try
        {
            idles.addAll(idleConnections);
            idleConnections.clear();
            actives.addAll(activeConnections);
            activeConnections.clear();
        }
        finally
        {
            unlock();
        }

        connectionCount.set(0);

        for (Connection connection : idles)
            connection.close();

        // A bit drastic, but we cannot wait for all requests to complete
        for (Connection connection : actives)
            connection.close();
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Connection> actives = new ArrayList<>();
        List<Connection> idles = new ArrayList<>();
        lock();
        try
        {
            actives.addAll(activeConnections);
            idles.addAll(idleConnections);
        }
        finally
        {
            unlock();
        }

        ContainerLifeCycle.dumpObject(out, this);
        ContainerLifeCycle.dump(out, indent, actives, idles);
    }

    @Override
    public boolean sweep()
    {
        List<Connection> toSweep = new ArrayList<>();
        lock();
        try
        {
            for (Connection connection : activeConnections)
            {
                if (connection instanceof Sweeper.Sweepable)
                    toSweep.add(connection);
            }
        }
        finally
        {
            unlock();
        }

        for (Connection connection : toSweep)
        {
            if (((Sweeper.Sweepable)connection).sweep())
            {
                boolean removed = remove(connection, true);
                LOG.warn("Connection swept: {}{}{} from active connections{}{}",
                        connection,
                        System.lineSeparator(),
                        removed ? "Removed" : "Not removed",
                        System.lineSeparator(),
                        dump());
            }
        }

        return false;
    }

    protected void lock()
    {
        lock.lock();
    }

    protected void unlock()
    {
        lock.unlock();
    }

    @Override
    public String toString()
    {
        int activeSize;
        int idleSize;
        lock();
        try
        {
            activeSize = activeConnections.size();
            idleSize = idleConnections.size();
        }
        finally
        {
            unlock();
        }

        return String.format("%s[c=%d/%d,a=%d,i=%d]",
                getClass().getSimpleName(),
                connectionCount.get(),
                maxConnections,
                activeSize,
                idleSize);
    }
}
