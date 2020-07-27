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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiplexConnectionPool extends AbstractConnectionPool implements ConnectionPool.Multiplexable, Sweeper.Sweepable
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiplexConnectionPool.class);

    private final AutoLock lock = new AutoLock();
    private final Deque<Holder> idleConnections;
    private final Map<Connection, Holder> activeConnections;
    private int maxMultiplex;

    public MultiplexConnectionPool(HttpDestination destination, int maxConnections, Callback requester, int maxMultiplex)
    {
        super(destination, maxConnections, requester);
        this.idleConnections = new ArrayDeque<>(maxConnections);
        this.activeConnections = new LinkedHashMap<>(maxConnections);
        this.maxMultiplex = maxMultiplex;
    }

    @Override
    public Connection acquire(boolean create)
    {
        Connection connection = activate();
        if (connection == null && create)
        {
            int queuedRequests = getHttpDestination().getQueuedRequestCount();
            int maxMultiplex = getMaxMultiplex();
            int maxPending = ceilDiv(queuedRequests, maxMultiplex);
            tryCreate(maxPending);
            connection = activate();
        }
        return connection;
    }

    /**
     * @param a the dividend
     * @param b the divisor
     * @return the ceiling of the algebraic quotient
     */
    private static int ceilDiv(int a, int b)
    {
        return (a + b - 1) / b;
    }

    @Override
    public int getMaxMultiplex()
    {
        return lock.runLocked(() -> maxMultiplex);
    }

    @Override
    public void setMaxMultiplex(int maxMultiplex)
    {
        lock.runLocked(() -> this.maxMultiplex = maxMultiplex);
    }

    @Override
    public boolean accept(Connection connection)
    {
        boolean accepted = super.accept(connection);
        if (LOG.isDebugEnabled())
            LOG.debug("Accepted {} {}", accepted, connection);
        if (accepted)
        {
            try (AutoLock ignored = lock.lock())
            {
                Holder holder = new Holder(connection);
                activeConnections.put(connection, holder);
                ++holder.count;
            }
            active(connection);
        }
        return accepted;
    }

    @Override
    public boolean isActive(Connection connection)
    {
        return lock.runLocked(() -> activeConnections.containsKey(connection));
    }

    @Override
    protected void onCreated(Connection connection)
    {
        // Use "cold" connections as last.
        lock.runLocked(() -> idleConnections.offer(new Holder(connection)));
        idle(connection, false);
    }

    @Override
    protected Connection activate()
    {
        Holder result = null;
        try (AutoLock ignored = lock.lock())
        {
            for (Holder holder : activeConnections.values())
            {
                if (holder.count < maxMultiplex)
                {
                    result = holder;
                    break;
                }
            }

            if (result == null)
            {
                Holder holder = idleConnections.poll();
                if (holder == null)
                    return null;
                activeConnections.put(holder.connection, holder);
                result = holder;
            }

            ++result.count;
        }
        return active(result.connection);
    }

    @Override
    public boolean release(Connection connection)
    {
        boolean closed = isClosed();
        boolean idle = false;
        Holder holder;
        try (AutoLock ignored = lock.lock())
        {
            holder = activeConnections.get(connection);
            if (holder != null)
            {
                int count = --holder.count;
                if (count == 0)
                {
                    activeConnections.remove(connection);
                    if (!closed)
                    {
                        idleConnections.offerFirst(holder);
                        idle = true;
                    }
                }
            }
        }
        if (holder == null)
            return false;

        released(connection);
        if (idle || closed)
            return idle(connection, closed);
        return true;
    }

    @Override
    public boolean remove(Connection connection)
    {
        return remove(connection, false);
    }

    protected boolean remove(Connection connection, boolean force)
    {
        boolean activeRemoved = true;
        boolean idleRemoved = false;
        try (AutoLock ignored = lock.lock())
        {
            Holder holder = activeConnections.remove(connection);
            if (holder == null)
            {
                activeRemoved = false;
                for (Iterator<Holder> iterator = idleConnections.iterator(); iterator.hasNext(); )
                {
                    holder = iterator.next();
                    if (holder.connection == connection)
                    {
                        idleRemoved = true;
                        iterator.remove();
                        break;
                    }
                }
            }
        }
        if (activeRemoved || force)
            released(connection);
        boolean removed = activeRemoved || idleRemoved || force;
        if (removed)
            removed(connection);
        return removed;
    }

    @Override
    public void close()
    {
        super.close();
        List<Connection> connections;
        try (AutoLock ignored = lock.lock())
        {
            connections = idleConnections.stream().map(holder -> holder.connection).collect(Collectors.toList());
            connections.addAll(activeConnections.keySet());
        }
        close(connections);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        DumpableCollection active;
        DumpableCollection idle;
        try (AutoLock ignored = lock.lock())
        {
            active = new DumpableCollection("active", new ArrayList<>(activeConnections.values()));
            idle = new DumpableCollection("idle", new ArrayList<>(idleConnections));
        }
        Dumpable.dumpObjects(out, indent, this, active, idle);
    }

    @Override
    public boolean sweep()
    {
        List<Connection> toSweep = new ArrayList<>();
        try (AutoLock ignored = lock.lock())
        {
            activeConnections.values().stream()
                .map(holder -> holder.connection)
                .filter(connection -> connection instanceof Sweeper.Sweepable)
                .collect(Collectors.toCollection(() -> toSweep));
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
        try (AutoLock ignored = lock.lock())
        {
            activeSize = activeConnections.size();
            idleSize = idleConnections.size();
        }
        return String.format("%s@%x[connections=%d/%d/%d,multiplex=%d,active=%d,idle=%d]",
            getClass().getSimpleName(),
            hashCode(),
            getPendingConnectionCount(),
            getConnectionCount(),
            getMaxConnectionCount(),
            getMaxMultiplex(),
            activeSize,
            idleSize);
    }

    private static class Holder
    {
        private final Connection connection;
        private int count;

        private Holder(Connection connection)
        {
            this.connection = connection;
        }

        @Override
        public String toString()
        {
            return String.format("%s[%d]", connection, count);
        }
    }
}
