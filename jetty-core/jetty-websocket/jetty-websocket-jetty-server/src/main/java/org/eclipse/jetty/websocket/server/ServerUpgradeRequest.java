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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.api.ExtensionConfig;

/**
 * <p>The HTTP request to upgrade to WebSocket.</p>
 * <p>An instance of this class is given as a parameter to a
 * {@link WebSocketCreator}, so that applications can interact
 * with the request.</p>
 *
 * @see ServerUpgradeResponse
 */
public interface ServerUpgradeRequest extends Request
{
    /**
     * @return the list of extensions provided by the client
     */
    List<ExtensionConfig> getExtensions();

    /**
     * @return the list of sub-protocols provided by the client
     */
    List<String> getSubProtocols();

    /**
     * @param subProtocol the sub-protocol to search
     * @return whether this request contains the given sub-protocol
     */
    boolean hasSubProtocol(String subProtocol);
}
