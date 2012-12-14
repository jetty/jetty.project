//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.examples.echo;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class EchoBroadcastSocket
{
    private static final ConcurrentLinkedQueue<EchoBroadcastSocket> BROADCAST = new ConcurrentLinkedQueue<EchoBroadcastSocket>();

    protected WebSocketConnection conn;

    @OnWebSocketMessage
    public void onBinary(byte buf[], int offset, int len)
    {
        for (EchoBroadcastSocket sock : BROADCAST)
        {
            sock.conn.write(buf,offset,len);
        }
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        BROADCAST.remove(this);
    }

    @OnWebSocketConnect
    public void onOpen(WebSocketConnection conn)
    {
        this.conn = conn;
        BROADCAST.add(this);
    }

    @OnWebSocketMessage
    public void onText(String text)
    {
        for (EchoBroadcastSocket sock : BROADCAST)
        {
            sock.conn.write(text);
        }
    }
}
