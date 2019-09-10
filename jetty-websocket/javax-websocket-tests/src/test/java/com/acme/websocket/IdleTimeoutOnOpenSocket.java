//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package com.acme.websocket;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.core.WebSocketTimeoutException;

@ServerEndpoint(value = "/idle-onopen-socket")
public class IdleTimeoutOnOpenSocket
{
    @OnOpen
    public void onOpen(Session session)
    {
        session.setMaxIdleTimeout(500);
    }

    @OnMessage
    public String onMessage(String msg)
    {
        return msg;
    }

    @OnError
    public void onError(Throwable cause)
    {
        if (!(cause instanceof WebSocketTimeoutException))
            throw new RuntimeException(cause);
    }
}
