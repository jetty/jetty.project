//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.server;

import org.eclipse.jetty.util.Callback;

/**
 * <p>Allows to create (custom) WebSocket endpoints instances given the HTTP request and HTTP response.</p>
 */
public interface WebSocketCreator
{
    /**
     * <p>Creates a WebSocket endpoint instance from the incoming request.</p>
     * <p>If the implementation does not want to create a WebSocket endpoint instance,
     * it is responsible to send a response (and completing the callback) and then it
     * may return {@code null}.</p>
     * <p>If the implementation returns a non-{@code null} WebSocket endpoint instance,
     * it must not write response content, nor completing the callback, but it may
     * modify the response headers.</p>
     *
     * @param upgradeRequest the upgrade request
     * @param upgradeResponse the upgrade response
     * @param callback the callback to complete only when returning {@code null}
     * @return a WebSocket endpoint instance, or {@code null} if no WebSocket endpoint
     * instance should be created for the given upgrade request
     * @throws Exception if the WebSocket endpoint instance creation fails
     */
    Object createWebSocket(ServerUpgradeRequest upgradeRequest, ServerUpgradeResponse upgradeResponse, Callback callback) throws Exception;
}
