//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.annotations;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.io.WebSocketBlockingConnection;

/**
 * The most common websocket implementation.
 * <p>
 * This version tracks the connection per socket instance and will
 */
@WebSocket
public class MyEchoSocket
{
    private WebSocketConnection conn;
    private WebSocketBlockingConnection blocking;

    public WebSocketBlockingConnection getConnection()
    {
        return blocking;
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        this.conn = null;
    }

    @OnWebSocketConnect
    public void onConnect(WebSocketConnection conn)
    {
        this.conn = conn;
        this.blocking = new WebSocketBlockingConnection(conn);
    }

    @OnWebSocketMessage
    public void onText(String message)
    {
        if (conn == null)
        {
            // no connection, do nothing.
            // this is possible due to async behavior
            return;
        }

        try
        {
            blocking.write(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
