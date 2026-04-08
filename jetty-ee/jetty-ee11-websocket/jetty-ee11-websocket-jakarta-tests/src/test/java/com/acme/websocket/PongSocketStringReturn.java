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

package com.acme.websocket;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.util.BufferUtil;

@ServerEndpoint(value = "/pong-socket-string-return", configurator = PongContextListener.Config.class)
public class PongSocketStringReturn
{
    private String path;

    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        path = (String)config.getUserProperties().get("path");
    }

    @OnMessage
    public String onPong(PongMessage pong)
    {
        return "PongSocket.onPong(PongMessage)[" + path + "]:" + BufferUtil.toString(pong.getApplicationData());
    }
}
