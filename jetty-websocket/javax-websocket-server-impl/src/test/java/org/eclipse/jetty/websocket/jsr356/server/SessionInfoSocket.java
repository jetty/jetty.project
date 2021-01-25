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

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint(value = "/info/")
public class SessionInfoSocket
{
    @OnMessage
    public String onMessage(Session session, String message)
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
            return ret.toString();
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
            return ret.toString();
        }

        // simple echo
        return "echo:'" + message + "'";
    }
}
