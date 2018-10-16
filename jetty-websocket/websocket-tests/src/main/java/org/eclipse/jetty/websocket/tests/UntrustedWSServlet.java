//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

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
        
        UntrustedWSSessionFactory sessionFactory = new UntrustedWSSessionFactory(serverFactory);
        this.getServletContext().setAttribute(UntrustedWSSessionFactory.class.getName(), sessionFactory);
        serverFactory.setSessionFactories(sessionFactory);
    }
}
