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

/**
 * Abstract WebSocket creator interface.
 * <p>
 * Should you desire filtering of the WebSocket object creation due to criteria such as origin or sub-protocol, then you will be required to implement a custom
 * WebSocketCreator implementation.
 * </p>
 */
public interface JettyWebSocketCreator
{
    /**
     * Create a websocket from the incoming request.
     *
     * @param req the request details
     * @param resp the response details
     * @return a websocket object to use, or null if no websocket should be created from this request.
     */
    Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp);
}
