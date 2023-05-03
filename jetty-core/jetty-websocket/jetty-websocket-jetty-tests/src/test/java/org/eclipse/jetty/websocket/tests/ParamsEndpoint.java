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

package org.eclipse.jetty.websocket.tests;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@SuppressWarnings("unused")
@WebSocket
public class ParamsEndpoint
{
    @OnWebSocketOpen
    public void onOpen(Session session)
    {
        Map<String, List<String>> params = session.getUpgradeRequest().getParameterMap();
        StringBuilder msg = new StringBuilder();

        for (String key : params.keySet())
        {
            msg.append("Params[").append(key).append("]=");
            msg.append(params.get(key).stream().collect(Collectors.joining(", ", "[", "]")));
            msg.append("\n");
        }

        session.sendText(msg.toString(), Callback.NOOP);
    }
}
