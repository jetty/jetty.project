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

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.common.events.EventCapture;

public class ListenerBasicSocket implements WebSocketListener
{
    public EventCapture capture = new EventCapture();

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        capture.add("onWebSocketBinary([%d], %d, %d)",payload.length,offset,len);
    }

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
    public void onWebSocketText(String message)
    {
        capture.add("onWebSocketText(%s)",capture.q(message));
    }
}
