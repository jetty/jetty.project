//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package examples;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.eclipse.jetty.websocket.common.events.EventCapture;

public class ListenerPingPongSocket implements WebSocketPingPongListener
{
    public EventCapture capture = new EventCapture();

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        capture.add("onWebSocketClose(%d, %s)",statusCode,capture.q(reason));
    }

    @Override
    public void onWebSocketConnect(Session session)
    {
        capture.add("onWebSocketConnect(%s)",session);
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        capture.add("onWebSocketError((%s) %s)",cause.getClass().getSimpleName(),cause.getMessage());
    }

    @Override
    public void onWebSocketPing(ByteBuffer payload)
    {
        capture.add("onWebSocketPing(%d)",payload.remaining());
    }

    @Override
    public void onWebSocketPong(ByteBuffer payload)
    {
        capture.add("onWebSocketPong(%d)",payload.remaining());
    }
}
