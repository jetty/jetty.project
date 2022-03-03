//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.demos;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WriteCallback;
import org.eclipse.jetty.ee9.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;

/**
 * Example of setting up a Jetty WebSocket server
 * <p>
 * Note: this uses the Jetty WebSocket API, not the jakarta.websocket API.
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
            session.getRemote().sendString(message, WriteCallback.NOOP);
        }
    }

    /**
     * Servlet layer
     */
    @SuppressWarnings("serial")
    public static class EchoServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.addMapping("/", (req, res) -> new EchoSocket());
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

        // Configure context to support WebSocket
        JettyWebSocketServletContainerInitializer.configure(context, null);

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
