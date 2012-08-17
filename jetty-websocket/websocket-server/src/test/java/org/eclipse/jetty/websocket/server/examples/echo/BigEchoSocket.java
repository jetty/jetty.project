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

import java.io.IOException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Example Socket for echoing back Big data using the Annotation techniques along with stateless techniques.
 */
@WebSocket(maxTextSize = 64 * 1024, maxBinarySize = 64 * 1024)
public class BigEchoSocket
{
    @OnWebSocketMessage
    public void onBinary(WebSocketConnection conn, byte buf[], int offset, int length)
    {
        if (conn.isOpen())
        {
            return;
        }
        try
        {
            conn.write(null,new FutureCallback<Void>(),buf,offset,length);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @OnWebSocketMessage
    public void onText(WebSocketConnection conn, String message)
    {
        if (conn.isOpen())
        {
            return;
        }
        try
        {
            conn.write(null,new FutureCallback<Void>(),message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
