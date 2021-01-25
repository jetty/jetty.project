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

import java.io.InputStream;
import java.io.Reader;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.events.EventCapture;

@WebSocket
public class AnnotatedStreamingSocket
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

    @OnWebSocketFrame
    public void onFrame(Frame frame)
    {
    }

    // Binary
    @OnWebSocketMessage
    public void onMessage(byte[] buf, int offset, int length)
    {
    }

    // Binary
    @OnWebSocketMessage
    public void onMessage(InputStream stream)
    {
    }

    // Text
    @OnWebSocketMessage
    public void onMessage(Reader stream)
    {
    }

    // Text
    @OnWebSocketMessage
    public void onMessage(String message)
    {
    }
}
