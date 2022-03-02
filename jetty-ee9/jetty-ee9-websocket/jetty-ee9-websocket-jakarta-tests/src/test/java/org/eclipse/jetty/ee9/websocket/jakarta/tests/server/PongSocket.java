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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.nio.charset.StandardCharsets;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/pong-socket", configurator = PongContextListener.Config.class)
public class PongSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(PongSocket.class);
    private String path = "?";
    private Session session;

    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        this.path = (String)config.getUserProperties().get("path");
    }

    @OnMessage
    public void onPong(PongMessage pong)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("PongSocket.onPong(): PongMessage.appData={}", BufferUtil.toDetailString(pong.getApplicationData()));
        byte[] buf = BufferUtil.toArray(pong.getApplicationData());
        String message = new String(buf, StandardCharsets.UTF_8);
        this.session.getAsyncRemote().sendText("PongSocket.onPong(PongMessage)[" + path + "]:" + message);
    }
}
