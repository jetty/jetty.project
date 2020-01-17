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

package org.eclipse.jetty.websocket.jsr356;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;

public class JsrSessionTracker extends AbstractLifeCycle implements JsrSessionListener
{
    private CopyOnWriteArraySet<JsrSession> sessions = new CopyOnWriteArraySet<>();

    public Set<javax.websocket.Session> getSessions()
    {
        return Collections.unmodifiableSet(sessions);
    }

    @Override
    public void onSessionOpened(JsrSession session)
    {
        sessions.add(session);
    }

    @Override
    public void onSessionClosed(JsrSession session)
    {
        sessions.remove(session);
    }

    @Override
    protected void doStop() throws Exception
    {
        for (JsrSession session : sessions)
        {
            LifeCycle.stop(session);
        }
        super.doStop();
    }
}
