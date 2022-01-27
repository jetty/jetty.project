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

package com.acme.websocket;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;

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
