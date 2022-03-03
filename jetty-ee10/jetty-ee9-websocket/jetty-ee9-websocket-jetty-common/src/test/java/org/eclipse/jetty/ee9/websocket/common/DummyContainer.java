//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketContainer;
import org.eclipse.jetty.ee9.websocket.api.WebSocketSessionListener;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class DummyContainer extends ContainerLifeCycle implements WebSocketContainer
{
    private final QueuedThreadPool executor;
    private final List<WebSocketSessionListener> sessionListeners = new ArrayList<>();
    private final SessionTracker sessionTracker = new SessionTracker();

    public DummyContainer()
    {
        executor = new QueuedThreadPool();
        executor.setName("dummy-container");
        addBean(executor);

        addSessionListener(sessionTracker);
        addBean(sessionTracker);
    }

    @Override
    public Executor getExecutor()
    {
        return executor;
    }

    @Override
    public Collection<Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
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
                x.printStackTrace(System.err);
            }
        }
    }
}
