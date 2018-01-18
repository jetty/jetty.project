//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * Always reply with the same text message.
 * <p>
 * Sent automatically on connect,
 * will also reply with message on any received text message.
 * </p>
 */
@WebSocket
public class TextMessageSocket
{
    private final String staticMessage;

    public TextMessageSocket(String message)
    {
        this.staticMessage = message;
    }

    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        try
        {
            session.getRemote().sendText(staticMessage);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @OnWebSocketMessage
    public void onText(Session session, String message)
    {
        try
        {
            session.getRemote().sendText(staticMessage);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
