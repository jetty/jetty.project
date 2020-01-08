//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.javax.common;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.websocket.CloseReason;
import javax.websocket.Session;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class SessionTracker extends AbstractLifeCycle implements JavaxWebSocketSessionListener
{
    private CopyOnWriteArraySet<JavaxWebSocketSession> sessions = new CopyOnWriteArraySet<>();

    public Set<Session> getSessions()
    {
        return Collections.unmodifiableSet(sessions);
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
    protected void doStop() throws Exception
    {
        for (Session session : sessions)
        {
            // GOING_AWAY is abnormal close status so it will hard close connection after sent.
            session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Container being shut down"));
        }

        super.doStop();
    }
}
