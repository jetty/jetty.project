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

package org.eclipse.jetty.websocket.server.examples;

import java.io.IOException;

import org.eclipse.jetty.websocket.server.examples.echo.BigEchoSocket;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

@SuppressWarnings("serial")
public class MyCustomCreationServlet extends WebSocketServlet
{
    public static class MyCustomCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            String query = req.getQueryString();

            // Start looking at the UpgradeRequest to determine what you want to do
            if ((query == null) || (query.length() <= 0))
            {
                try
                {
                    // Let UPGRADE request for websocket fail with
                    // status code 403 (FORBIDDEN) [per RFC-6455]
                    resp.sendForbidden("Unspecified query");
                }
                catch (IOException e)
                {
                    // An input or output exception occurs
                    e.printStackTrace();
                }

                // No UPGRADE
                return null;
            }

            // Create the websocket we want to
            if (query.contains("bigecho"))
            {
                return new BigEchoSocket();
            }
            else if (query.contains("echo"))
            {
                return new MyEchoSocket();
            }

            // Let UPGRADE fail with 503 (UNAVAILABLE)
            return null;
        }
    }

    @Override
    public void configure(WebSocketServletFactory factory)
    {
        factory.setCreator(new MyCustomCreator());
    }
}
