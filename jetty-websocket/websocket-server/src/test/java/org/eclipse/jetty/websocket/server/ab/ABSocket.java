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

package org.eclipse.jetty.websocket.server.ab;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.util.TextUtil;

/**
 * Simple Echo WebSocket, using async writes of echo
 */
@WebSocket
public class ABSocket
{
    private static Logger LOG = Log.getLogger(ABSocket.class);

    private Session session;

    @OnWebSocketMessage
    public void onBinary(byte buf[], int offset, int len)
    {
        LOG.debug("onBinary(byte[{}],{},{})",buf.length,offset,len);

        // echo the message back.
        ByteBuffer data = ByteBuffer.wrap(buf,offset,len);
        this.session.getRemote().sendBytes(data,null);
    }

    @OnWebSocketConnect
    public void onOpen(Session sess)
    {
        this.session = sess;
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
                LOG.debug("onText() size={}, msg={}",message.length(),TextUtil.hint(message));
            }
        }

        try
        {
            // echo the message back.
            this.session.getRemote().sendString(message,null);
        }
        catch (WebSocketException e)
        {
            LOG.warn("Unable to echo TEXT message",e);
        }
    }
}
