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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

public class SessionInfoEndpoint extends Endpoint implements MessageHandler.Whole<String>
{
    private Session session;

    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        this.session.addMessageHandler(this);
    }

    @Override
    public void onMessage(String message)
    {
        try
        {
            if ("pathParams".equalsIgnoreCase(message))
            {
                StringBuilder ret = new StringBuilder();
                ret.append("pathParams");
                Map<String, String> pathParams = session.getPathParameters();
                if (pathParams == null)
                {
                    ret.append("=<null>");
                }
                else
                {
                    ret.append('[').append(pathParams.size()).append(']');
                    List<String> keys = new ArrayList<>();
                    for (String key : pathParams.keySet())
                    {
                        keys.add(key);
                    }
                    Collections.sort(keys);
                    for (String key : keys)
                    {
                        String value = pathParams.get(key);
                        ret.append(": '").append(key).append("'=").append(value);
                    }
                }
                session.getBasicRemote().sendText(ret.toString());
                return;
            }

            if ("requestUri".equalsIgnoreCase(message))
            {
                StringBuilder ret = new StringBuilder();
                ret.append("requestUri=");
                URI uri = session.getRequestURI();
                if (uri == null)
                {
                    ret.append("=<null>");
                }
                else
                {
                    ret.append(uri.toASCIIString());
                }
                session.getBasicRemote().sendText(ret.toString());
                return;
            }

            // simple echo
            session.getBasicRemote().sendText("echo:'" + message + "'");
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }
}
