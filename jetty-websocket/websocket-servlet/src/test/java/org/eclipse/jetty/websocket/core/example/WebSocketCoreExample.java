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

package org.eclipse.jetty.websocket.core.example;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.core.example.impl.WebSocketSessionFactory;

public class WebSocketCoreExample
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();

        ServerConnector connector = new ServerConnector(
                server,
                new HttpConnectionFactory(),
                new ExampleWebSocketConnectionFactory()
        );

        connector.setPort(8080);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");
        server.setHandler(context);
        context.setAttribute(WebSocketSessionFactory.class.getName(), new ExampleWebSocketSessionFactory());

        ExampleWebSocketHandler handler = new ExampleWebSocketHandler();
        context.setHandler(handler);
        handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().println("Hello World!");
                baseRequest.setHandled(true);
            }
        });

        server.start();
        server.join();
    }
}
