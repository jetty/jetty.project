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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server.sockets.echo;

import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

/**
 * Annotated echo socket
 */
@ServerEndpoint(value = "/echo/large")
public class LargeEchoConfiguredSocket
{
    private Session session;

    @OnOpen
    public void open(Session session)
    {
        this.session = session;
        this.session.setMaxTextMessageBufferSize(128 * 1024);
    }

    @OnMessage
    public void echo(String msg)
    {
        // reply with echo
        session.getAsyncRemote().sendText(msg);
    }
}
