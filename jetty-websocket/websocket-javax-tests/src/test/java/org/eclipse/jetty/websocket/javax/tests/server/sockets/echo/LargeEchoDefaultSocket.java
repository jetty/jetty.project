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

package org.eclipse.jetty.websocket.javax.tests.server.sockets.echo;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

/**
 * Annotated echo socket (default behavior as defined from {@link WebSocketContainer#setDefaultMaxTextMessageBufferSize(int)})
 */
@ServerEndpoint(value = "/echo/large")
public class LargeEchoDefaultSocket
{
    @OnMessage
    public void echo(Session session, String msg)
    {
        // reply with echo
        session.getAsyncRemote().sendText(msg);
    }
}
