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

package examples;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.common.events.EventCapture;

public class ListenerPartialSocket implements WebSocketPartialListener
{
    public EventCapture capture = new EventCapture();

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        capture.offer("onWebSocketClose(%d, %s)", statusCode, capture.q(reason));
    }

    @Override
    public void onWebSocketConnect(Session session)
    {
        capture.offer("onWebSocketConnect(%s)", session);
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        capture.offer("onWebSocketError((%s) %s)", cause.getClass().getSimpleName(), cause.getMessage());
    }

    @Override
    public void onWebSocketPartialText(String payload, boolean fin)
    {
        capture.offer("onWebSocketPartialText('%s', %b)", payload, fin);
    }

    @Override
    public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
    {
        capture.offer("onWebSocketPartialBinary(%s [%d], %b)", payload, payload.remaining(), fin);
    }
}
