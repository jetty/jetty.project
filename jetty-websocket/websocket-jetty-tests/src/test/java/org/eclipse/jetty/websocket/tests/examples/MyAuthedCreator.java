//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
