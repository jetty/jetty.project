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

package org.eclipse.jetty.websocket.javax.common;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.websocket.Session;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;

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
        for (JavaxWebSocketSession session : sessions)
        {
            LifeCycle.stop(session);
        }
        super.doStop();
    }
}
