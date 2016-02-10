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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

@ManagedObject("The connection pool")
public class DuplexConnectionPool extends AbstractConnectionPool implements Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(DuplexConnectionPool.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Connection> idleConnections;
    private final Set<Connection> activeConnections;

    public DuplexConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        super(destination, maxConnections, requester);
        this.idleConnections = new ArrayDeque<>(maxConnections);
        this.activeConnections = new HashSet<>(maxConnections);
    }

    protected void lock()
    {
        lock.lock();
    }

    protected void unlock()
    {
        lock.unlock();
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

    public Collection<Connection> getActiveConnections()
    {
        return activeConnections;
    }

    @Override
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

    @Override
    protected void onCreated(Connection connection)
    {
        lock();
        try
        {
            // Use "cold" new connections as last.
            idleConnections.offer(connection);
        }
        finally
        {
            unlock();
        }

        idle(connection, false);
    }

    @Override
    protected Connection activate()
    {
        Connection connection;
        lock();
        try
        {
            connection = idleConnections.poll();
            if (connection == null)
                return null;
            activeConnections.add(connection);
        }
        finally
        {
            unlock();
        }

        return active(connection);
    }

    public boolean release(Connection connection)
    {
        boolean closed = isClosed();
        lock();
        try
        {
            if (!activeConnections.remove(connection))
                return false;

            if (!closed)
            {
                // Make sure we use "hot" connections first.
                deactivate(connection);
            }
        }
        finally
        {
            unlock();
        }

        released(connection);
        return idle(connection, closed);
    }

    protected boolean deactivate(Connection connection)
    {
        return idleConnections.offerFirst(connection);
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
            removed(connection);
        return removed;
    }

    public void close()
    {
        super.close();

        List<Connection> connections = new ArrayList<>();
        lock();
        try
        {
            connections.addAll(idleConnections);
            idleConnections.clear();
            connections.addAll(activeConnections);
            activeConnections.clear();
        }
        finally
        {
            unlock();
        }

        close(connections);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Connection> connections = new ArrayList<>();
        lock();
        try
        {
            connections.addAll(activeConnections);
            connections.addAll(idleConnections);
        }
        finally
        {
            unlock();
        }

        ContainerLifeCycle.dumpObject(out, this);
        ContainerLifeCycle.dump(out, indent, connections);
    }

    @Override
    public boolean sweep()
    {
        List<Connection> toSweep;
        lock();
        try
        {
            toSweep = activeConnections.stream()
                    .filter(connection -> connection instanceof Sweeper.Sweepable)
                    .collect(Collectors.toList());
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

        return String.format("%s@%x[c=%d/%d,a=%d,i=%d]",
                getClass().getSimpleName(),
                hashCode(),
                getConnectionCount(),
                getMaxConnectionCount(),
                activeSize,
                idleSize);
    }
}
