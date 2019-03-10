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

package org.eclipse.jetty.websocket.server.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.SessionTracker;
import org.eclipse.jetty.websocket.common.WebSocketContainer;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;

public class JettyWebSocketServerContainer implements WebSocketContainer
{
    private final static Logger LOG = Log.getLogger(JettyWebSocketServerContainer.class);
    private final Executor executor;
    private final List<WebSocketSessionListener> sessionListeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();

    public JettyWebSocketServerContainer(ContextHandler handler)
    {
        Executor executor = (Executor) handler
                .getAttribute("org.eclipse.jetty.server.Executor");
        if (executor == null)
        {
            executor = handler.getServer().getThreadPool();
        }
        if (executor == null)
        {
            executor = new QueuedThreadPool(); // default settings
        }
        this.executor = executor;
        addSessionListener(sessionTracker);
        handler.addBean(sessionTracker);
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        sessionListeners.add(listener);
    }

    @Override
    public boolean removeSessionListener(WebSocketSessionListener listener)
    {
        return sessionListeners.remove(listener);
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> consumer)
    {
        for (WebSocketSessionListener listener : sessionListeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
    }

    @Override
    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }
}
