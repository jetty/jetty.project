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

package org.eclipse.jetty.websocket.server.ab;

import java.io.IOException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

/**
 * Simple Echo WebSocket, using async writes of echo
 */
@WebSocket
public class ABSocket
{
    private static Logger LOG = Log.getLogger(ABSocket.class);

    private WebSocketConnection conn;

    private String abbreviate(String message)
    {
        if (message.length() > 80)
        {
            return '"' + message.substring(0,80) + "\"...";
        }
        return '"' + message + '"';
    }

    @OnWebSocketMessage
    public void onBinary(byte buf[], int offset, int len)
    {
        LOG.debug("onBinary(byte[{}],{},{})",buf.length,offset,len);

        // echo the message back.
        try
        {
            this.conn.write(null,new FutureCallback<Void>(),buf,offset,len);
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }

    @OnWebSocketConnect
    public void onOpen(WebSocketConnection conn)
    {
        this.conn = conn;
    }

    @OnWebSocketMessage
    public void onText(String message)
    {
        if (LOG.isDebugEnabled())
        {
            if (message == null)
            {
                LOG.debug("onText() msg=null");
            }
            else
            {
                LOG.debug("onText() size={}, msg={}",message.length(),abbreviate(message));
            }
        }

        // echo the message back.
        try
        {
            this.conn.write(null,new FutureCallback<Void>(),message);
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}
