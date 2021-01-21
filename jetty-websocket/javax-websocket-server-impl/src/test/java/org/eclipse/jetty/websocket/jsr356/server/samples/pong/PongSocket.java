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

package org.eclipse.jetty.websocket.jsr356.server.samples.pong;

import java.nio.charset.StandardCharsets;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ServerEndpoint(value = "/pong-socket", configurator = PongContextListener.Config.class)
public class PongSocket
{
    private static final Logger LOG = Log.getLogger(PongSocket.class);
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
            LOG.debug("onPong(): PongMessage.appData={}", BufferUtil.toDetailString(pong.getApplicationData()));
        byte[] buf = BufferUtil.toArray(pong.getApplicationData());
        String message = new String(buf, StandardCharsets.UTF_8);
        this.session.getAsyncRemote().sendText("@OnMessage(PongMessage)[" + path + "]:" + message);
    }
}
