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

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.events.EventCapture;

@WebSocket
public class AnnotatedBinaryArraySocket
{
    public EventCapture capture = new EventCapture();

    @OnWebSocketMessage
    public void onBinary(byte[] payload, int offset, int length)
    {
        capture.offer("onBinary([%d],%d,%d)", payload.length, offset, length);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        capture.offer("onClose(%d, %s)", statusCode, capture.q(reason));
    }

    @OnWebSocketConnect
    public void onConnect(Session sess)
    {
        capture.offer("onConnect(%s)", sess);
    }
}
