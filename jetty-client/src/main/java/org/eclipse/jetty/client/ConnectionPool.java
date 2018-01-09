//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

public class ConnectionPool implements Closeable, Dumpable, Sweeper.Sweepable
{
    protected static final Logger LOG = Log.getLogger(ConnectionPool.class);

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Destination destination;
    private final int maxConnections;
    private final Promise<Connection> requester;
    private final BlockingDeque<Connection> idleConnections;
    private final BlockingQueue<Connection> activeConnections;

    public ConnectionPool(Destination destination, int maxConnections, Promise<Connection> requester)
    {
        this.destination = destination;
        this.maxConnections = maxConnections;
        this.requester = requester;
        this.idleConnections = new LinkedBlockingDeque<>(maxConnections);
        this.activeConnections = new BlockingArrayQueue<>(maxConnections);
    }

    public int getConnectionCount()
    {
        return connectionCount.get();
    }

    public BlockingQueue<Connection> getIdleConnections()
    {
        return idleConnections;
    }

    public BlockingQueue<Connection> getActiveConnections()
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

                        requester.succeeded(connection);
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

    protected void idleCreated(Connection connection)
    {
        boolean idle;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            // Use "cold" new connections as last.
            idle = idleConnections.offerLast(connection);
        }
        finally
        {
            lock.unlock();
        }

        idle(connection, idle);
    }

    private Connection activateIdle()
    {
        boolean acquired;
        Connection connection;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            connection = idleConnections.pollFirst();
            if (connection == null)
                return null;
            acquired = activeConnections.offer(connection);
        }
        finally
        {
            lock.unlock();
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            if (!activeConnections.remove(connection))
                return false;
            // Make sure we use "hot" connections first.
            idle = idleConnections.offerFirst(connection);
        }
        finally
        {
            lock.unlock();
        }

        released(connection);
        return idle(connection, idle);
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
        boolean activeRemoved;
        boolean idleRemoved;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            activeRemoved = activeConnections.remove(connection);
            idleRemoved = idleConnections.remove(connection);
        }
        finally
        {
            lock.unlock();
        }

        if (activeRemoved)
            released(connection);
        boolean removed = activeRemoved || idleRemoved;
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            return activeConnections.contains(connection);
        }
        finally
        {
            lock.unlock();
        }
    }

    public boolean isIdle(Connection connection)
    {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            return idleConnections.contains(connection);
        }
        finally
        {
            lock.unlock();
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            idles.addAll(idleConnections);
            idleConnections.clear();
            actives.addAll(activeConnections);
            activeConnections.clear();
        }
        finally
        {
            lock.unlock();
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
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            actives.addAll(activeConnections);
            idles.addAll(idleConnections);
        }
        finally
        {
            lock.unlock();
        }

        ContainerLifeCycle.dumpObject(out, this);
        ContainerLifeCycle.dump(out, indent, actives, idles);
    }

    @Override
    public boolean sweep()
    {
        List<Sweeper.Sweepable> toSweep = new ArrayList<>();
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            for (Connection connection : getActiveConnections())
            {
                if (connection instanceof Sweeper.Sweepable)
                    toSweep.add(((Sweeper.Sweepable)connection));
            }
        }
        finally
        {
            lock.unlock();
        }

        for (Sweeper.Sweepable candidate : toSweep)
        {
            if (candidate.sweep())
            {
                boolean removed = getActiveConnections().remove(candidate);
                LOG.warn("Connection swept: {}{}{} from active connections{}{}",
                        candidate,
                        System.lineSeparator(),
                        removed ? "Removed" : "Not removed",
                        System.lineSeparator(),
                        dump());
            }
        }

        return false;
    }

    @Override
    public String toString()
    {
        int activeSize;
        int idleSize;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try
        {
            activeSize = activeConnections.size();
            idleSize = idleConnections.size();
        }
        finally
        {
            lock.unlock();
        }

        return String.format("%s[c=%d/%d,a=%d,i=%d]",
                getClass().getSimpleName(),
                connectionCount.get(),
                maxConnections,
                activeSize,
                idleSize);
    }
}
