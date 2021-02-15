//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.server.samples.echo;

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
