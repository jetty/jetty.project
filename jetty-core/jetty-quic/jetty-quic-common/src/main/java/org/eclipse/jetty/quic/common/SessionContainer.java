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

package org.eclipse.jetty.quic.common;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Graceful;

/**
 * <p>A container that tracks {@link Session} instances.</p>
 */
@ManagedObject("The container of QUIC sessions")
public class SessionContainer extends AbstractLifeCycle implements Session.Listener, Graceful, Dumpable
{
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private CompletableFuture<Void> shutdown;

    @Override
    public void onOpen(Session session)
    {
        boolean isShutDown = false;
        lock.readLock().lock();
        try
        {
            if (shutdown == null)
                sessions.add(session);
            else
                isShutDown = true;
        }
        finally
        {
            lock.readLock().unlock();
        }
        if (isShutDown)
            shutdown(session);
    }

    @Override
    public void onDisconnect(Session session, ConnectionCloseFrame frame)
    {
        lock.readLock().lock();
        try
        {
            sessions.remove(session);
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
            CompletableFuture<?>[] shutdowns = sessions.stream().map(this::shutdown).toArray(CompletableFuture[]::new);
            return shutdown = CompletableFuture.allOf(shutdowns);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    private CompletableFuture<Session> shutdown(Session session)
    {
        return ((AbstractSession)session).shutdown();
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

    public boolean isEmpty()
    {
        return sessions.isEmpty();
    }

    @ManagedAttribute("The number of QUIC sessions in this container")
    public int getSize()
    {
        return sessions.size();
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("sessions", sessions));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[size=%d]", TypeUtil.toShortName(getClass()), hashCode(), sessions.size());
    }
}
