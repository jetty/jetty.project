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
public class RoundRobinConnectionPool extends AbstractConnectionPool
{
    private final List<Entry> entries;
    private int index;

    public RoundRobinConnectionPool(Destination destination, int maxConnections, Callback requester)
    {
        super(destination, maxConnections, requester);
        entries = new ArrayList<>(maxConnections);
        for (int i = 0; i < maxConnections; ++i)
            entries.add(new Entry());
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

                if (!entry.active)
                {
                    entry.active = true;
                    entry.used++;
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
                    return entry.active;
            }
            return false;
        }
    }

    @Override
    public boolean release(Connection connection)
    {
        boolean active = false;
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection == connection)
                {
                    entry.active = false;
                    active = true;
                    break;
                }
            }
        }
        if (active)
            released(connection);
        return idle(connection, isClosed());
    }

    @Override
    public boolean remove(Connection connection)
    {
        boolean active = false;
        boolean removed = false;
        synchronized (this)
        {
            for (Entry entry : entries)
            {
                if (entry.connection == connection)
                {
                    active = entry.active;
                    entry.reset();
                    removed = true;
                    break;
                }
            }
        }
        if (active)
            released(connection);
        if (removed)
            removed(connection);
        return removed;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<Entry> connections = new ArrayList<>();
        synchronized (this)
        {
            connections.addAll(entries);
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
                    if (entry.active)
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
        private boolean active;
        private long used;

        private void reset()
        {
            connection = null;
            active = false;
            used = 0;
        }

        @Override
        public String toString()
        {
            return String.format("{u=%d,c=%s}", used, connection);
        }
    }
}
