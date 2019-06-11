//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

public class MultiplexConnectionPool extends AbstractConnectionPool implements ConnectionPool.Multiplexable, Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(MultiplexConnectionPool.class);

    private final HttpDestination destination;
    private final Deque<Holder> idleConnections;
    private final Map<Connection, Holder> activeConnections;
    private int maxMultiplex;

    public MultiplexConnectionPool(HttpDestination destination, int maxConnections, Callback requester, int maxMultiplex)
    {
        super(destination, maxConnections, requester);
        this.destination = destination;
        this.idleConnections = new ArrayDeque<>(maxConnections);
        this.activeConnections = new LinkedHashMap<>(maxConnections);
        this.maxMultiplex = maxMultiplex;
    }

    @Override
    public Connection acquire()
    {
        Connection connection = activate();
        if (connection == null)
        {
            int maxPending = 1 + destination.getQueuedRequestCount() / getMaxMultiplex();
            tryCreate(maxPending);
            connection = activate();
        }
        return connection;
    }

    @Override
    public int getMaxMultiplex()
    {
        synchronized (this)
        {
            return maxMultiplex;
        }
    }

    @Override
    public void setMaxMultiplex(int maxMultiplex)
    {
        synchronized (this)
        {
            this.maxMultiplex = maxMultiplex;
        }
    }

    @Override
    public boolean isActive(Connection connection)
    {
        synchronized (this)
        {
            return activeConnections.containsKey(connection);
        }
    }

    @Override
    protected void onCreated(Connection connection)
    {
        synchronized (this)
        {
            // Use "cold" connections as last.
            idleConnections.offer(new Holder(connection));
        }
        idle(connection, false);
    }

    @Override
    protected Connection activate()
    {
        Holder result = null;
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
        {
            activeSize = activeConnections.size();
            idleSize = idleConnections.size();
        }
        return String.format("%s@%x[connections=%d/%d,multiplex=%d,active=%d,idle=%d]",
                getClass().getSimpleName(),
                hashCode(),
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
