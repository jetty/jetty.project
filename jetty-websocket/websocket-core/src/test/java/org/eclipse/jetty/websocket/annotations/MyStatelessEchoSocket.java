// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.annotations;

import java.io.IOException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Example of a stateless websocket implementation.
 * <p>
 * Useful for websockets that only reply to incoming requests.
 * <p>
 * Note: that for this style of websocket to be viable on the server side be sure that you only create 1 instance of this socket, as more instances would be
 * wasteful of resources and memory.
 */
@WebSocket
public class MyStatelessEchoSocket
{
    @OnWebSocketMessage
    public void onText(WebSocketConnection conn, String text)
    {
        try
        {
            conn.write(null,new FutureCallback<Void>(),text);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
