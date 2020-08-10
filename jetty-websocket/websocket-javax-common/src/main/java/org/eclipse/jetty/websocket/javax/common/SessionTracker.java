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

package org.eclipse.jetty.websocket.javax.common;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.websocket.CloseReason;
import javax.websocket.Session;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Graceful;

public class SessionTracker extends AbstractLifeCycle implements JavaxWebSocketSessionListener, Graceful
{
    private final Set<JavaxWebSocketSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean isShutdown = false;

    public Set<Session> getSessions()
    {
        return Set.copyOf(sessions);
    }

    @Override
    public void onJavaxWebSocketSessionOpened(JavaxWebSocketSession session)
    {
        sessions.add(session);
    }

    @Override
    public void onJavaxWebSocketSessionClosed(JavaxWebSocketSession session)
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

                // GOING_AWAY is abnormal close status so it will hard close connection after sent.
                session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Container being shut down"));
            }
        });
    }

    @Override
    public boolean isShutdown()
    {
        return isShutdown;
    }
}
