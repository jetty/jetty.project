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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Example of setting up a Jetty WebSocket server
 * <p>
 * Note: this uses the Jetty WebSocket API, not the javax.websocket API.
 */
public class WebSocketServer
{
    /**
     * Example of a Jetty API WebSocket Echo Socket
     */
    @WebSocket
    public static class EchoSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            session.getRemote().sendStringByFuture(message);
        }
    }

    /**
     * Servlet layer
     */
    @SuppressWarnings("serial")
    public static class EchoServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            // Register the echo websocket with the basic WebSocketCreator
            factory.register(EchoSocket.class);
        }
    }

    public static Server createServer(int port)
    {
        Server server = new Server(port);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Add the echo socket servlet to the /echo path map
        context.addServlet(EchoServlet.class, "/echo");
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
