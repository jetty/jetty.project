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

package org.eclipse.jetty.websocket.jakarta.common;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionTracker extends AbstractLifeCycle implements JakartaWebSocketSessionListener
{
    private static final Logger LOG = LoggerFactory.getLogger(SessionTracker.class);

    private CopyOnWriteArraySet<JakartaWebSocketSession> sessions = new CopyOnWriteArraySet<>();

    public Set<Session> getSessions()
    {
        return Collections.unmodifiableSet(sessions);
    }

    @Override
    public void onJakartaWebSocketSessionOpened(JakartaWebSocketSession session)
    {
        sessions.add(session);
    }

    @Override
    public void onJakartaWebSocketSessionClosed(JakartaWebSocketSession session)
    {
        sessions.remove(session);
    }

    @Override
    protected void doStop() throws Exception
    {
        for (Session session : sessions)
        {
            try
            {
                // GOING_AWAY is abnormal close status so it will hard close connection after sent.
                session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Container being shut down"));
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
            }
        }

        super.doStop();
    }
}
