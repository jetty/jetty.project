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

package org.eclipse.jetty.ee10.websocket.common;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.Graceful;

public class SessionTracker extends AbstractLifeCycle implements WebSocketSessionListener, Graceful, Dumpable
{
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean isShutdown = false;

    public Collection<Session> getSessions()
    {
        return Set.copyOf(sessions);
    }

    @Override
    public void onWebSocketSessionOpened(Session session)
    {
        sessions.add(session);
    }

    @Override
    public void onWebSocketSessionClosed(Session session)
    {
        sessions.remove(session);
    }

    @Override
    protected void doStart() throws Exception
    {
        isShutdown = false;
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        sessions.clear();
        super.doStop();
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        isShutdown = true;
        return Graceful.shutdown(() ->
        {
            for (Session session : sessions)
            {
                if (Thread.interrupted())
                    break;

                // SHUTDOWN is abnormal close status so it will hard close connection after sent.
                session.close(StatusCode.SHUTDOWN, "Container being shut down");
            }
        });
    }

    @Override
    public boolean isShutdown()
    {
        return isShutdown;
    }

    @ManagedAttribute("Total number of active WebSocket Sessions")
    public int getNumSessions()
    {
        return sessions.size();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, sessions);
    }
}
