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

package org.eclipse.jetty.ee9.websocket.server;

import java.util.Set;

import org.eclipse.jetty.ee9.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.ee9.websocket.api.WebSocketPolicy;

public interface JettyWebSocketServletFactory extends WebSocketPolicy
{
    /**
     * Add a WebSocket mapping to a provided {@link JettyWebSocketCreator}.
     * <p>
     * If mapping is added before this configuration is started, then it is persisted through
     * stop/start of this configuration's lifecycle.  Otherwise it will be removed when
     * this configuration is stopped.
     * </p>
     *
     * @param pathSpec the pathspec to respond on
     * @param creator the WebSocketCreator to use
     * @since 10.0
     */
    void addMapping(String pathSpec, JettyWebSocketCreator creator);

    /**
     * Add a WebSocket mapping at PathSpec "/" for a creator which creates the endpointClass
     *
     * @param endpointClass the WebSocket class to use
     */
    void register(Class<?> endpointClass);

    /**
     * Add a WebSocket mapping at PathSpec "/" for a creator
     *
     * @param creator the WebSocketCreator to use
     */
    void setCreator(JettyWebSocketCreator creator);

    /**
     * Returns the creator for the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return the websocket creator if path spec exists, or null
     */
    JettyWebSocketCreator getMapping(String pathSpec);

    /**
     * Removes the mapping based on the given path spec.
     *
     * @param pathSpec the pathspec to respond on
     * @return true if underlying mapping were altered, false otherwise
     */
    boolean removeMapping(String pathSpec);

    /**
     * Get the names of all available WebSocket Extensions.
     * @return a set the available extension names.
     */
    Set<String> getAvailableExtensionNames();

    @Override
    default WebSocketBehavior getBehavior()
    {
        return WebSocketBehavior.SERVER;
    }
}
