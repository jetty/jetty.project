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

package org.eclipse.jetty.websocket.server.examples.echo;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

/**
 * Example of setting up a creator to create appropriately via the proposed and negotiated protocols.
 */
public class EchoCreator implements WebSocketCreator
{
    private BigEchoSocket bigEchoSocket = new BigEchoSocket();
    private EchoFragmentSocket echoFragmentSocket = new EchoFragmentSocket();
    private LogSocket logSocket = new LogSocket();

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        for (String protocol : req.getSubProtocols())
        {
            switch (protocol)
            {
                case "org.ietf.websocket.test-echo":
                case "echo":
                    resp.setAcceptedSubProtocol(protocol);
                    // TODO: how is this different than "echo-assemble"?
                    return bigEchoSocket;
                case "org.ietf.websocket.test-echo-broadcast":
                case "echo-broadcast":
                    resp.setAcceptedSubProtocol(protocol);
                    return new EchoBroadcastSocket();
                case "echo-broadcast-ping":
                    resp.setAcceptedSubProtocol(protocol);
                    return new EchoBroadcastPingSocket();
                case "org.ietf.websocket.test-echo-assemble":
                case "echo-assemble":
                    resp.setAcceptedSubProtocol(protocol);
                    // TODO: how is this different than "test-echo"?
                    return bigEchoSocket;
                case "org.ietf.websocket.test-echo-fragment":
                case "echo-fragment":
                    resp.setAcceptedSubProtocol(protocol);
                    return echoFragmentSocket;
                default:
                    return logSocket;
            }
        }
        return null;
    }
}
