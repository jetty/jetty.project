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

package org.eclipse.jetty.websocket.core.server;

import org.eclipse.jetty.util.Callback;

/**
 * Abstract WebSocket creator interface.
 * <p>
 * This can be used for filtering of the WebSocket object creation due to criteria such as origin or sub-protocol,
 * or for choosing a specific WebSocket object based on the upgrade request.
 * </p>
 */
public interface WebSocketCreator
{
    /**
     * Create a websocket from the incoming request.
     *
     * <p>If the creator returns null it is responsible for completing the {@link Callback} and sending a response.
     * If the creator intends to return non-null WebSocket object, it MUST NOT write content to the response or
     * complete the {@link Callback}.</p>
     *
     * @param req the request details
     * @param resp the response details
     * @param callback the callback, should only be completed by the creator if a null WebSocket object is returned.
     * @return the WebSocket object, or null to take responsibility to send error response if no WebSocket is to be created.
     */
    Object createWebSocket(ServerUpgradeRequest req, ServerUpgradeResponse resp, Callback callback);
}
