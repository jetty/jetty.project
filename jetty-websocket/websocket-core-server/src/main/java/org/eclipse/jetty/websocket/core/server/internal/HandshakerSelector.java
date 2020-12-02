//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

/**
 * Selects between the two Handshaker implementations,
 * RFC6455 (HTTP/1.1 WebSocket Upgrades)
 * and RFC68441 (HTTP/2 WebSocket Upgrades)
 */
public class HandshakerSelector implements Handshaker
{
    private final RFC6455Handshaker rfc6455 = new RFC6455Handshaker();
    private final RFC8441Handshaker rfc8441 = new RFC8441Handshaker();

    @Override
    public boolean upgradeRequest(WebSocketNegotiator negotiator, HttpServletRequest request, HttpServletResponse response, WebSocketComponents components, Configuration.Customizer defaultCustomizer) throws IOException
    {
        // Try HTTP/1.1 WS upgrade, if this fails try an HTTP/2 WS upgrade if no response was committed.
        return rfc6455.upgradeRequest(negotiator, request, response, components, defaultCustomizer) ||
            !response.isCommitted() && rfc8441.upgradeRequest(negotiator, request, response, components, defaultCustomizer);
    }
}
