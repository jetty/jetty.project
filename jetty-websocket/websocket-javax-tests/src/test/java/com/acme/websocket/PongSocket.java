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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.websocket.EndpointConfig;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/pong-socket", configurator = PongContextListener.Config.class)
public class PongSocket
{
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
        byte[] buf = toArray(pong.getApplicationData());
        String message = new String(buf, StandardCharsets.UTF_8);
        this.session.getAsyncRemote().sendText("PongSocket.onPong(PongMessage)[" + path + "]:" + message);
    }

    public static byte[] toArray(ByteBuffer buffer)
    {
        if (buffer.hasArray())
        {
            byte[] array = buffer.array();
            int from = buffer.arrayOffset() + buffer.position();
            return Arrays.copyOfRange(array, from, from + buffer.remaining());
        }
        else
        {
            byte[] to = new byte[buffer.remaining()];
            buffer.slice().get(to);
            return to;
        }
    }
}
