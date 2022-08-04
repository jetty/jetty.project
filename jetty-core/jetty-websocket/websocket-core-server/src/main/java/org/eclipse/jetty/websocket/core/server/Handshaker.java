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

import java.io.IOException;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.internal.HandshakerSelector;

public interface Handshaker
{
    static Handshaker newInstance()
    {
        return new HandshakerSelector();
    }

    boolean isWebSocketUpgradeRequest(Request request);

    /**
     * This will attempt to upgrade a request to WebSocket.
     *
     * <p>This method returns true if a WebSocket upgrade was attempted in which case this method takes responsibility for
     * completing the callback and generating a response, the request may be upgraded to WebSocket or some error response
     * will be sent. If this method returns false the WebSocket upgrade was not accepted and the caller is still responsible
     * for completing the callback and generating a response.</p>
     *
     * @param negotiator the negotiator
     * @param request the request
     * @param response the response
     * @param callback the callback
     * @param components the WebSocket components
     * @param defaultCustomizer the customizer
     * @return true if the WebSocket upgrade was attempted
     * @throws IOException there is an error during the upgrade
     */
    boolean upgradeRequest(WebSocketNegotiator negotiator, Request request, Response response, Callback callback, WebSocketComponents components, Configuration.Customizer defaultCustomizer) throws IOException;
}
