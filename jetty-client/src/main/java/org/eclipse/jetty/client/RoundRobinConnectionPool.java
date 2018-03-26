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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

@ManagedObject
public class RoundRobinConnectionPool extends AbstractConnectionPool implements ConnectionPool.Multiplexable
{
    private final List<Entry> entries;
    private int maxMultiplex;
    private int index;

    public RoundRobinConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        this(destination, maxConnections, requester, 1);
    }

    public RoundRobinConnectionPool(Destination destination, int maxConnections, Callback requester, int maxMultiplex)
    {
        super(destination, maxConnections, requester);
        entries = new ArrayList<>(maxConnections);
        for (int i = 0; i < maxConnections; ++i)
            entries.add(new Entry());
        this.maxMultiplex = maxMultiplex;
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
    protected void onCreated(Connection connection)
    {
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection == null)
                {
                    entry.connection = connection;
                    break;
                }
            }
        }
        idle(connection, false);
    }

    @Override
    protected Connection activate()
    {
        Connection connection = null;
        synchronized (this)
        {
            int offset = 0;
            int capacity = getMaxConnectionCount();
            while (offset < capacity)
            {
                int idx = index + offset;
                if (idx >= capacity)
                    idx -= capacity;

                Entry entry = entries.get(idx);

                if (entry.connection == null)
                    break;

                if (entry.active < getMaxMultiplex())
                {
                    ++entry.active;
                    ++entry.used;
                    connection = entry.connection;
                    index += offset + 1;
                    if (index >= capacity)
                        index -= capacity;
                    break;
                }

                ++offset;
            }
        }
        return connection == null ? null : active(connection);
    }

    @Override
    public boolean isActive(Connection connection)
    {
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection == connection)
                    return entry.active > 0;
            }
            return false;
        }
    }

    @Override
    public boolean release(Connection connection)
    {
        boolean found = false;
        boolean idle = false;
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection == connection)
                {
                    found = true;
                    int active = --entry.active;
                    idle = active == 0;
                    break;
                }
            }
        }
        if (!found)
            return false;
        released(connection);
        if (idle)
            return idle(connection, isClosed());
        return true;
    }

    @Override
    public boolean remove(Connection connection)
    {
        boolean found = false;
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection == connection)
                {
                    found = true;
                    entry.reset();
                    break;
                }
            }
        }
        if (found)
        {
            released(connection);
            removed(connection);
        }
        return found;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Entry> connections;
        synchronized (this)
        {
            connections = new ArrayList<>(entries);
        }
        ContainerLifeCycle.dumpObject(out, this);
        ContainerLifeCycle.dump(out, indent, connections);
    }

    @Override
    public String toString()
    {
        int present = 0;
        int active = 0;
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection != null)
                {
                    ++present;
                    if (entry.active > 0)
                        ++active;
                }
            }
        }
        return String.format("%s@%x[c=%d/%d,a=%d]",
                getClass().getSimpleName(),
                hashCode(),
                present,
                getMaxConnectionCount(),
                active
        );
    }

    private static class Entry
    {
        private Connection connection;
        private int active;
        private long used;

        private void reset()
        {
            connection = null;
            active = 0;
            used = 0;
        }

        @Override
        public String toString()
        {
            return String.format("{u=%d,c=%s}", used, connection);
        }
    }
}
