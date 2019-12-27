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

package org.eclipse.jetty.websocket.common;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketSessionListener;

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
