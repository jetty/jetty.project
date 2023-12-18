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

import java.util.List;

import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.api.ExtensionConfig;

/**
 * <p>The HTTP response to upgrade to WebSocket.</p>
 * <p>An instance of this class is given as a parameter to a
 * {@link WebSocketCreator}, so that applications can interact
 * with the response.</p>
 */
public interface ServerUpgradeResponse extends Response
{
    /**
     * @return the negotiated sub-protocol
     */
    String getAcceptedSubProtocol();

    /**
     * Set the negotiated sub-protocol.
     * @param protocol the negotiated sub-protocol
     */
    void setAcceptedSubProtocol(String protocol);

    /**
     * @return the list of negotiated extensions
     */
    List<ExtensionConfig> getExtensions();

    /**
     * Set the list of negotiated extensions.
     * @param configs the list of negotiated extensions
     */
    void setExtensions(List<ExtensionConfig> configs);
}
