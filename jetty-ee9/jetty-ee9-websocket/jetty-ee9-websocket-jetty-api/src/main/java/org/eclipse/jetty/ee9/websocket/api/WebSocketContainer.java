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

package org.eclipse.jetty.ee9.websocket.api;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Generic interface to the Container (server or client)
 */
public interface WebSocketContainer
{
    /**
     * The Container provided Executor.
     */
    Executor getExecutor();

    /**
     * Get the collection of open Sessions being tracked by this container
     *
     * @return the collection of open sessions
     */
    Collection<Session> getOpenSessions();

    /**
     * Register a WebSocketSessionListener with the container
     *
     * @param listener the listener
     */
    void addSessionListener(WebSocketSessionListener listener);

    /**
     * Remove a WebSocketSessionListener from the container
     *
     * @param listener the listener
     * @return true if listener was present and removed
     */
    boolean removeSessionListener(WebSocketSessionListener listener);

    /**
     * Notify the Session Listeners of an event.
     *
     * @param consumer the consumer to call for each tracked listener
     */
    void notifySessionListeners(Consumer<WebSocketSessionListener> consumer);
}
