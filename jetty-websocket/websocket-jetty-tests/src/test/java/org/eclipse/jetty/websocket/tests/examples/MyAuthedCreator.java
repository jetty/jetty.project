//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.examples;

import java.io.IOException;
import java.security.Principal;

import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.JettyWebSocketCreator;

public class MyAuthedCreator implements JettyWebSocketCreator
{
    @Override
    public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp)
    {
        try
        {
            // Is Authenticated?
            Principal principal = req.getUserPrincipal();
            if (principal == null)
            {
                resp.sendForbidden("Not authenticated yet");
                return null;
            }

            // Is Authorized?
            if (!req.isUserInRole("websocket"))
            {
                resp.sendForbidden("Not authenticated yet");
                return null;
            }

            // Return websocket
            return new MyEchoSocket();
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
        // no websocket
        return null;
    }
}
