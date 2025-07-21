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

package org.eclipse.jetty.websocket.core.server;

import java.util.List;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

/**
 * Upgrade request used for websocket negotiation.
 * <p>
 * Provides getters for things like the requested extensions and subprotocols so that the headers don't have to be parsed manually.
 * <p>
 * This should only be used during the websocket negotiation.
 */
public interface ServerUpgradeRequest extends Request
{
    /**
     * @return The WebSocket components used for this upgrade request
     */
    WebSocketComponents getWebSocketComponents();

    /**
     * @return The extensions offered
     */
    List<ExtensionConfig> getExtensions();

    /**
     * @return WebSocket protocol version from "Sec-WebSocket-Version" header
     */
    String getProtocolVersion();

    /**
     * @return Get WebSocket negotiation offered sub protocols
     */
    List<String> getSubProtocols();

    /**
     * @param subprotocol A sub protocol name
     * @return True if the sub protocol was offered
     */
    boolean hasSubProtocol(String subprotocol);
}
