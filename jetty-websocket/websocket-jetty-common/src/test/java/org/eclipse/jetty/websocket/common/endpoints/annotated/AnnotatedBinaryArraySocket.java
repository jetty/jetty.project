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

package org.eclipse.jetty.websocket.common.endpoints.annotated;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.EventQueue;
import org.eclipse.jetty.websocket.util.TextUtil;

@WebSocket
public class AnnotatedBinaryArraySocket
{
    public EventQueue events = new EventQueue();

    @OnWebSocketMessage
    public void onBinary(byte[] payload, int offset, int length)
    {
        events.add("onBinary([%d],%d,%d)", payload.length, offset, length);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        events.add("onClose(%d, %s)", statusCode, TextUtil.quote(reason));
    }

    @OnWebSocketConnect
    public void onConnect(Session sess)
    {
        events.add("onConnect(%s)", sess);
    }
}
