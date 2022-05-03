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

package org.eclipse.jetty.ee9.websocket.tests.examples;

import java.nio.ByteBuffer;

import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;

/**
 * Echo BINARY messages
 */
@WebSocket
public class MyBinaryEchoSocket
{
    @OnWebSocketMessage
    public void onWebSocketText(Session session, byte[] buf, int offset, int len)
    {
        // Echo message back, asynchronously
        session.getRemote().sendBytes(ByteBuffer.wrap(buf, offset, len), null);
    }
}
