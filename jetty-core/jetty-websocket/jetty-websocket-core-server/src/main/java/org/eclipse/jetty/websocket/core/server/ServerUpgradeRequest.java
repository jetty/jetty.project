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
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketComponents;

public interface ServerUpgradeRequest extends Request
{
    WebSocketComponents getWebSocketComponents();

    void upgrade(Attributes attributes);

    List<ExtensionConfig> getExtensions();

    String getProtocolVersion();

    List<String> getSubProtocols();

    boolean hasSubProtocol(String subprotocol);
}
