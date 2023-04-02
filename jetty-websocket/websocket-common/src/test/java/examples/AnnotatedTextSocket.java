//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.events.EventCapture;

@WebSocket
public class AnnotatedTextSocket
{
    public EventCapture capture = new EventCapture();

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

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        capture.offer("onError(%s: %s)", cause.getClass().getSimpleName(), cause.getMessage());
    }

    @OnWebSocketMessage
    public void onText(String message)
    {
        capture.offer("onText(%s)", capture.q(message));
    }
}