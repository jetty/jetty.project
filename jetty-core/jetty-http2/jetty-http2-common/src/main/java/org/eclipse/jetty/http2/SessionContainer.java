//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * <p>A container of HTTP/2 {@link Session} instances.</p>
 * <p>This container is part of the Jetty component tree,
 * but the session instances are not part of the component
 * tree for performance reasons.</p>
 * <p>This container ensures that the session instances are
 * dumped as if they were part of the component tree.</p>
 */
@ManagedObject("The container of HTTP/2 sessions")
public class SessionContainer extends AbstractLifeCycle implements Connection.Listener, Graceful, Dumpable
{
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private CompletableFuture<Void> shutdown;

    @Override
    public void onOpened(Connection connection)
    {
        boolean isShutDown;
        Session session = ((HTTP2Connection)connection).getSession();
        lock.readLock().lock();
        try
        {
            isShutDown = shutdown != null;
            if (!isShutDown)
            {
                sessions.add(session);
                LifeCycle.start(session);
            }
        }
        finally
        {
            lock.readLock().unlock();
        }
        if (isShutDown)
            session.shutdown();
    }

    @Override
    public void onClosed(Connection connection)
    {
        lock.readLock().lock();
        try
        {
            Session session = ((HTTP2Connection)connection).getSession();
            if (sessions.remove(session))
                LifeCycle.stop(session);
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        lock.writeLock().lock();
        try
        {
            if (shutdown != null)
                return shutdown;
            CompletableFuture<?>[] shutdowns = sessions.stream().map(Session::shutdown).toArray(CompletableFuture[]::new);
            return shutdown = CompletableFuture.allOf(shutdowns);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isShutdown()
    {
        lock.readLock().lock();
        try
        {
            return shutdown != null;
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    public Set<Session> getSessions()
    {
        return Collections.unmodifiableSet(sessions);
    }

    public int getSize()
    {
        return sessions.size();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("sessions", sessions));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[size=%d]", getClass().getSimpleName(), hashCode(), getSize());
    }
}
