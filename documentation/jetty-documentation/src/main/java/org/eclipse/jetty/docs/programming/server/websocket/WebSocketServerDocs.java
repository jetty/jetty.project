//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.server.websocket;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

@SuppressWarnings("unused")
public class WebSocketServerDocs
{
    public void standardExplicit() throws Exception
    {
        // tag::standardExplicit[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Configure the javax.websocket implementation for this web application context.
        JavaxWebSocketServletContainerInitializer.configure(handler, (context, container) ->
        {
            // Add your WebSocket endpoint(s) to the ServerContainer.
            container.addEndpoint(MyJavaxWebSocketEndPoint.class);
        });

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::standardExplicit[]
    }

    public void standardAutomatic() throws Exception
    {
        // tag::standardAutomatic[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a WebAppContext with the given context path.
        WebAppContext handler = new WebAppContext("/path/to/webapp", "/ctx");
        server.setHandler(handler);

        // Starting the Server will start the WebAppContext.
        server.start();
        // end::standardAutomatic[]
    }

    public void jettyExplicit() throws Exception
    {
        // tag::jettyExplicit[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Configure the Jetty WebSocket implementation for this web application context.
        JettyWebSocketServletContainerInitializer.configure(handler, (context, container) ->
        {
            // Add your WebSocket endpoint(s) to the ServerContainer.
            container.addMapping("/ws/myURI", MyJettyWebSocketEndPoint.class);

            // Use JettyWebSocketCreator to have more control on the WebSocket endpoint creation.
            container.addMapping("/ws/myOtherURI", (upgradeRequest, upgradeResponse) -> {
                // Possibly inspect the upgrade request and modify the upgrade response.

                // Create the new WebSocket endpoint.
                return new MyOtherJettyWebSocketEndPoint();
            });
        });

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::jettyExplicit[]
    }

    public void jettyDirect() throws Exception
    {
        // tag::jettyDirect[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Add your WebSocketServlet subclass to the ServletContextHandler.
        handler.addServlet(MyWebSocketServlet.class, "/ws/*");

        // Configure the Jetty WebSocket implementation for this web application context.
        JettyWebSocketServletContainerInitializer.configure(handler, null);

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::jettyDirect[]
    }

    public void jettyAutomatic() throws Exception
    {
        // tag::jettyAutomatic[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a WebAppContext with the given context path.
        WebAppContext handler = new WebAppContext("/path/to/webapp", "/ctx");
        server.setHandler(handler);

        // Starting the Server will start the WebAppContext.
        server.start();
        // end::jettyAutomatic[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::jettyDirectServlet[]
    public class MyWebSocketServlet extends JettyWebSocketServlet
    {
        @Override
        protected void configure(JettyWebSocketServletFactory factory)
        {
            // At most 1 MiB text messages.
            factory.setMaxTextMessageSize(1048576);

            // Add the WebSocket endpoint.
            factory.addMapping("/ws/someURI", (upgradeRequest, upgradeResponse) -> {
                // Possibly inspect the upgrade request and modify the upgrade response.

                // Create the new WebSocket endpoint.
                return new MyJettyWebSocketEndPoint();
            });
        }
    }
    // end::jettyDirectServlet[]

    private static class MyJavaxWebSocketEndPoint
    {
    }

    private static class MyJettyWebSocketEndPoint
    {
    }

    private static class MyOtherJettyWebSocketEndPoint
    {
    }
}
