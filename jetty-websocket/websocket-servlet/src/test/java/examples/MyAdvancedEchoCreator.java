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

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class MyAdvancedEchoCreator implements WebSocketCreator
{
    private MyBinaryEchoSocket binaryEcho;
    private MyEchoSocket textEcho;

    public MyAdvancedEchoCreator()
    {
        // Create the reusable sockets
        this.binaryEcho = new MyBinaryEchoSocket();
        this.textEcho = new MyEchoSocket();
    }

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        for (String subprotocol : req.getSubProtocols())
        {
            if ("binary".equals(subprotocol))
            {
                resp.setAcceptedSubProtocol(subprotocol);
                return binaryEcho;
            }
            if ("text".equals(subprotocol))
            {
                resp.setAcceptedSubProtocol(subprotocol);
                return textEcho;
            }
        }

        // No valid subprotocol in request, ignore the request
        return null;
    }
}
