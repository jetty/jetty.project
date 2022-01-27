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

package org.eclipse.jetty.websocket.javax.tests.server.sockets.echo;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import javax.websocket.CloseReason;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/echoreturn")
public class EchoReturnEndpoint
{
    private Session session = null;
    public CloseReason close = null;
    public LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();

    public void onClose(CloseReason close)
    {
        this.close = close;
    }

    @OnMessage
    public String onMessage(String message)
    {
        this.messageQueue.offer(message);
        // Return the message
        return message;
    }

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
    }

    public void sendText(String text) throws IOException
    {
        if (session != null)
        {
            session.getBasicRemote().sendText(text);
        }
    }
}
