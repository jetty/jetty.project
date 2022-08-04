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

    boolean upgradeRequest(WebSocketNegotiator negotiator, Request request, Response response, Callback callback, WebSocketComponents components, Configuration.Customizer defaultCustomizer) throws IOException;
}
