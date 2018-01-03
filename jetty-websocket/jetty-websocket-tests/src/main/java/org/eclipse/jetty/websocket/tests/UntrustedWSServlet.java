//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests;

import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

public class UntrustedWSServlet extends WebSocketServlet
{
    private final WebSocketCreator creator;
    
    @SuppressWarnings("unused")
    public UntrustedWSServlet()
    {
        this((req, resp) ->
        {
            UntrustedWSEndpoint endpoint = new UntrustedWSEndpoint(WebSocketBehavior.SERVER.name());
            if (req.hasSubProtocol("echo"))
            {
                endpoint.setOnTextFunction((session, payload) -> payload);
                endpoint.setOnBinaryFunction((session, payload) -> payload);
                resp.setAcceptedSubProtocol("echo");
            }
            return endpoint;
        });
    }
    
    public UntrustedWSServlet(WebSocketCreator creator)
    {
        this.creator = creator;
    }
    
    @Override
    public void configure(WebSocketServletFactory factory)
    {
        WebSocketServerFactory serverFactory = (WebSocketServerFactory) factory;
        serverFactory.setCreator(this.creator);
        serverFactory.getPolicy().setMaxTextMessageSize(100 * 1024);
        serverFactory.getPolicy().setMaxBinaryMessageSize(100 * 1024);

        serverFactory.setNewSessionFunction((connection) -> new UntrustedWSSession<>(connection));
    }
}
