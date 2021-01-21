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

import javax.servlet.annotation.WebServlet;

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

@SuppressWarnings("serial")
@WebServlet(name = "MyEcho WebSocket Servlet", urlPatterns = {"/echo"})
public class MyEchoServlet extends WebSocketServlet
{
    @Override
    public void configure(WebSocketServletFactory factory)
    {
        // set a 10 second timeout
        factory.getPolicy().setIdleTimeout(10000);

        // register MyEchoSocket as the WebSocket to create on Upgrade
        factory.register(MyEchoSocket.class);
    }
}
