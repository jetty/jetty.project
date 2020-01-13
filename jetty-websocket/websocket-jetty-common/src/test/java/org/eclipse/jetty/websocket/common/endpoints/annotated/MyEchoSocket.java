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
