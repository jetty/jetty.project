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

package org.eclipse.jetty.embedded;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;

/**
 * Example of setting up a javax.websocket server with Jetty embedded
 */
public class WebSocketJsrServer
{
    /**
     * A server socket endpoint
     */
    @ServerEndpoint(value = "/echo")
    public static class EchoJsrSocket
    {
        @OnMessage
        public void onMessage(Session session, String message)
        {
            session.getAsyncRemote().sendText(message);
        }
    }

    public static Server createServer(int port)
    {
        Server server = new Server(port);

        HandlerList handlers = new HandlerList();

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        handlers.addHandler(context);

        // Enable javax.websocket configuration for the context
        WebSocketServerContainerInitializer.configure(context,
            (servletContext, serverContainer) ->
            {
                // Add your websocket to the javax.websocket.server.ServerContainer
                serverContainer.addEndpoint(EchoJsrSocket.class);
            }
        );

        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        server.start();
        server.join();
    }
}
