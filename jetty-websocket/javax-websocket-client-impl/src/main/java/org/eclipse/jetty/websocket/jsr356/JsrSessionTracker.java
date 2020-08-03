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
import java.util.HashSet;
import java.util.Set;
import javax.websocket.Session;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;

public class JsrSessionTracker extends AbstractLifeCycle implements JsrSessionListener
{
    private final Set<JsrSession> sessions = new HashSet<>();

    public Set<Session> getSessions()
    {
        synchronized (this)
        {
            return Collections.unmodifiableSet(new HashSet<>(sessions));
        }
    }

    @Override
    public void onSessionOpened(JsrSession session)
    {
        synchronized (this)
        {
            sessions.add(session);
        }
    }

    @Override
    public void onSessionClosed(JsrSession session)
    {
        synchronized (this)
        {
            sessions.remove(session);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        for (Session session : getSessions())
        {
            LifeCycle.stop(session);
        }
        super.doStop();
    }
}
