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

package org.eclipse.jetty.websocket.common.annotations;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * The most common websocket implementation.
 * <p>
 * This version tracks the connection per socket instance and will
 */
@WebSocket
public class MyEchoSocket
{
    private Session session;
    private RemoteEndpoint remote;

    public RemoteEndpoint getRemote()
    {
        return remote;
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        this.session = null;
    }

    @OnWebSocketConnect
    public void onConnect(Session session)
    {
        this.session = session;
        this.remote = session.getRemote();
    }

    @OnWebSocketMessage
    public void onText(String message)
    {
        if (session == null)
        {
            // no connection, do nothing.
            // this is possible due to async behavior
            return;
        }

        try
        {
            remote.sendString(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
