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

package org.eclipse.jetty.embedded;

import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;

/**
 * Example of setting up a jakarta.websocket server with Jetty embedded
 */
public class WebSocketJakartaServer
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

        // Enable jakarta.websocket configuration for the context
        JakartaWebSocketServletContainerInitializer.configure(context,
            (servletContext, serverContainer) ->
            {
                // Add your websocket to the jakarta.websocket.server.ServerContainer
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
