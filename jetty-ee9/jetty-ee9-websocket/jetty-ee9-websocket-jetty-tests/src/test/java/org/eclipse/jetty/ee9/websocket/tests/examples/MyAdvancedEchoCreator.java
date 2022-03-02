//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.tests.examples;

import org.eclipse.jetty.ee9.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketCreator;

public class MyAdvancedEchoCreator implements JettyWebSocketCreator
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
    public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp)
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
