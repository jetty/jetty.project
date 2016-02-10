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
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

public class MultiplexConnectionPool extends AbstractConnectionPool implements Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(MultiplexConnectionPool.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Holder> idleConnections;
    private final Map<Connection, Holder> muxedConnections;
    private final Map<Connection, Holder> busyConnections;
    private int maxMultiplex;

    public MultiplexConnectionPool(HttpDestination destination, int maxConnections, Callback requester, int maxMultiplex)
    {
        super(destination, maxConnections, requester);
        this.idleConnections = new ArrayDeque<>(maxConnections);
        this.muxedConnections = new HashMap<>(maxConnections);
        this.busyConnections = new HashMap<>(maxConnections);
        this.maxMultiplex = maxMultiplex;
    }

    protected void lock()
    {
        lock.lock();
    }

    protected void unlock()
    {
        lock.unlock();
    }

    public int getMaxMultiplex()
    {
        lock();
        try
        {
            return maxMultiplex;
        }
        finally
        {
            unlock();
        }
    }

    public void setMaxMultiplex(int maxMultiplex)
    {
        lock();
        try
        {
            this.maxMultiplex = maxMultiplex;
        }
        finally
        {
            unlock();
        }
    }

    @Override
    public boolean isActive(Connection connection)
    {
        lock();
        try
        {
            if (muxedConnections.containsKey(connection))
                return true;
            if (busyConnections.containsKey(connection))
                return true;
            return false;
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
            // Use "cold" connections as last.
            idleConnections.offer(new Holder(connection));
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
        Holder holder;
        lock();
        try
        {
            while (true)
            {
                if (muxedConnections.isEmpty())
                {
                    holder = idleConnections.poll();
                    if (holder == null)
                        return null;
                    muxedConnections.put(holder.connection, holder);
                }
                else
                {
                    holder = muxedConnections.values().iterator().next();
                }

                if (holder.count < maxMultiplex)
                {
                    ++holder.count;
                    break;
                }
                else
                {
                    muxedConnections.remove(holder.connection);
                    busyConnections.put(holder.connection, holder);
                }
            }
        }
        finally
        {
            unlock();
        }

        return active(holder.connection);
    }

    @Override
    public boolean release(Connection connection)
    {
        boolean closed = isClosed();
        boolean idle = false;
        Holder holder;
        lock();
        try
        {
            holder = muxedConnections.get(connection);
            if (holder != null)
            {
                int count = --holder.count;
                if (count == 0)
                {
                    muxedConnections.remove(connection);
                    if (!closed)
                    {
                        idleConnections.offerFirst(holder);
                        idle = true;
                    }
                }
            }
            else
            {
                holder = busyConnections.remove(connection);
                if (holder != null)
                {
                    int count = --holder.count;
                    if (!closed)
                    {
                        if (count == 0)
                        {
                            idleConnections.offerFirst(holder);
                            idle = true;
                        }
                        else
                        {
                            muxedConnections.put(connection, holder);
                        }
                    }
                }
            }
        }
        finally
        {
            unlock();
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
        lock();
        try
        {
            Holder holder = muxedConnections.remove(connection);
            if (holder == null)
                holder = busyConnections.remove(connection);
            if (holder == null)
            {
                activeRemoved = false;
                for (Iterator<Holder> iterator = idleConnections.iterator(); iterator.hasNext();)
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

    @Override
    public void close()
    {
        super.close();

        List<Connection> connections;
        lock();
        try
        {
            connections = idleConnections.stream().map(holder -> holder.connection).collect(Collectors.toList());
            connections.addAll(muxedConnections.keySet());
            connections.addAll(busyConnections.keySet());
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
        List<Holder> connections = new ArrayList<>();
        lock();
        try
        {
            connections.addAll(busyConnections.values());
            connections.addAll(muxedConnections.values());
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
        List<Connection> toSweep = new ArrayList<>();
        lock();
        try
        {
            busyConnections.values().stream()
                    .map(holder -> holder.connection)
                    .filter(connection -> connection instanceof Sweeper.Sweepable)
                    .collect(Collectors.toCollection(() -> toSweep));
            muxedConnections.values().stream()
                    .map(holder -> holder.connection)
                    .filter(connection -> connection instanceof Sweeper.Sweepable)
                    .collect(Collectors.toCollection(() -> toSweep));
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
        int busySize;
        int muxedSize;
        int idleSize;
        lock();
        try
        {
            busySize = busyConnections.size();
            muxedSize = muxedConnections.size();
            idleSize = idleConnections.size();
        }
        finally
        {
            unlock();
        }
        return String.format("%s@%x[c=%d/%d,b=%d,m=%d,i=%d]",
                getClass().getSimpleName(),
                hashCode(),
                getConnectionCount(),
                getMaxConnectionCount(),
                busySize,
                muxedSize,
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
