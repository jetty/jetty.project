//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class SessionSocket
{
    private static final Logger LOG = Log.getLogger(SessionSocket.class);
    private Session session;
    
    @OnWebSocketConnect
    public void onConnect(Session sess)
    {
        this.session = sess;
    }
    
    @OnWebSocketMessage
    public void onText(String message)
    {
        LOG.debug("onText({})", message);
        if (message == null)
        {
            return;
        }
        
        try
        {
            if (message.startsWith("getParameterMap"))
            {
                Map<String, List<String>> parameterMap = session.getHandshakeRequest().getParameterMap();
                
                int idx = message.indexOf('|');
                String key = message.substring(idx + 1);
                List<String> values = parameterMap.get(key);
                
                if (values == null)
                {
                    session.getRemote().sendText("<null>");
                    return;
                }
                
                StringBuilder valueStr = new StringBuilder();
                valueStr.append('[');
                boolean delim = false;
                for (String value : values)
                {
                    if (delim)
                    {
                        valueStr.append(", ");
                    }
                    valueStr.append(value);
                    delim = true;
                }
                valueStr.append(']');
                LOG.debug("valueStr = {}", valueStr);
                session.getRemote().sendText(valueStr.toString());
                return;
            }
            
            if ("session.isSecure".equals(message))
            {
                String issecure = String.format("session.isSecure=%b", session.isSecure());
                session.getRemote().sendText(issecure);
                return;
            }
            
            if ("session.upgradeRequest.requestURI".equals(message))
            {
                String response = String.format("session.upgradeRequest.requestURI=%s", session.getHandshakeRequest().getRequestURI().toASCIIString());
                session.getRemote().sendText(response);
                return;
            }
            
            // echo the message back.
            session.getRemote().sendText(message);
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
    }
}
