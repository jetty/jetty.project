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

package org.eclipse.jetty.docs.programming.server.websocket;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketCreator;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;

@SuppressWarnings("unused")
public class WebSocketServerDocs
{
    public void standardContainerWebAppContext() throws Exception
    {
        // tag::standardContainerWebAppContext[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a WebAppContext with the given context path.
        WebAppContext handler = new WebAppContext("/path/to/webapp", "/ctx");
        server.setHandler(handler);

        // Starting the Server will start the WebAppContext.
        server.start();
        // end::standardContainerWebAppContext[]
    }

    public void standardContainerServletContextHandler() throws Exception
    {
        // tag::standardContainerServletContextHandler[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Ensure that JavaxWebSocketServletContainerInitializer is initialized,
        // to setup the ServerContainer for this web application context.
        JakartaWebSocketServletContainerInitializer.configure(handler, null);

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::standardContainerServletContextHandler[]
    }

    public void standardEndpointsInitialization() throws Exception
    {
        // tag::standardEndpointsInitialization[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Ensure that JavaxWebSocketServletContainerInitializer is initialized,
        // to setup the ServerContainer for this web application context.
        JakartaWebSocketServletContainerInitializer.configure(handler, null);

        // Add a WebSocket-initializer Servlet to register WebSocket endpoints.
        handler.addServlet(MyJavaxWebSocketInitializerServlet.class, "/*");

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::standardEndpointsInitialization[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::standardWebSocketInitializerServlet[]
    public class MyJavaxWebSocketInitializerServlet extends HttpServlet
    {
        @Override
        public void init() throws ServletException
        {
            try
            {
                // Retrieve the ServerContainer from the ServletContext attributes.
                ServerContainer container = (ServerContainer)getServletContext().getAttribute(ServerContainer.class.getName());

                // Configure the ServerContainer.
                container.setDefaultMaxTextMessageBufferSize(128 * 1024);

                // Simple registration of your WebSocket endpoints.
                container.addEndpoint(MyJavaxWebSocketEndPoint.class);

                // Advanced registration of your WebSocket endpoints.
                container.addEndpoint(
                    ServerEndpointConfig.Builder.create(MyJavaxWebSocketEndPoint.class, "/ws")
                        .subprotocols(List.of("my-ws-protocol"))
                        .build()
                );
            }
            catch (DeploymentException x)
            {
                throw new ServletException(x);
            }
        }
    }
    // end::standardWebSocketInitializerServlet[]

    public void standardContainerAndEndpoints() throws Exception
    {
        // tag::standardContainerAndEndpoints[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Setup the ServerContainer and the WebSocket endpoints for this web application context.
        JakartaWebSocketServletContainerInitializer.configure(handler, (servletContext, container) ->
        {
            // Configure the ServerContainer.
            container.setDefaultMaxTextMessageBufferSize(128 * 1024);

            // Simple registration of your WebSocket endpoints.
            container.addEndpoint(MyJavaxWebSocketEndPoint.class);

            // Advanced registration of your WebSocket endpoints.
            container.addEndpoint(
                ServerEndpointConfig.Builder.create(MyJavaxWebSocketEndPoint.class, "/ws")
                    .subprotocols(List.of("my-ws-protocol"))
                    .build()
            );
        });

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::standardContainerAndEndpoints[]
    }

    public void jettyContainerServletContextHandler() throws Exception
    {
        // tag::jettyContainerServletContextHandler[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Ensure that JettyWebSocketServletContainerInitializer is initialized,
        // to setup the JettyWebSocketServerContainer for this web application context.
        JettyWebSocketServletContainerInitializer.configure(handler, null);

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::jettyContainerServletContextHandler[]
    }

    public void jettyEndpointsInitialization() throws Exception
    {
        // tag::jettyEndpointsInitialization[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Ensure that JettyWebSocketServletContainerInitializer is initialized,
        // to setup the JettyWebSocketServerContainer for this web application context.
        JettyWebSocketServletContainerInitializer.configure(handler, null);

        // Add a WebSocket-initializer Servlet to register WebSocket endpoints.
        handler.addServlet(MyJettyWebSocketInitializerServlet.class, "/*");

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::jettyEndpointsInitialization[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::jettyWebSocketInitializerServlet[]
    public class MyJettyWebSocketInitializerServlet extends HttpServlet
    {
        @Override
        public void init() throws ServletException
        {
            // Retrieve the JettyWebSocketServerContainer.
            JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(getServletContext());

            // Configure the JettyWebSocketServerContainer.
            container.setMaxTextMessageSize(128 * 1024);

            // Simple registration of your WebSocket endpoints.
            container.addMapping("/ws/myURI", MyJettyWebSocketEndPoint.class);

            // Advanced registration of your WebSocket endpoints.
            container.addMapping("/ws/myOtherURI", (upgradeRequest, upgradeResponse) ->
                new MyOtherJettyWebSocketEndPoint()
            );
        }
    }
    // end::jettyWebSocketInitializerServlet[]

    public void jettyContainerAndEndpoints() throws Exception
    {
        // tag::jettyContainerAndEndpoints[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Setup the JettyWebSocketServerContainer and the WebSocket endpoints for this web application context.
        JettyWebSocketServletContainerInitializer.configure(handler, (servletContext, container) ->
        {
            // Configure the ServerContainer.
            container.setMaxTextMessageSize(128 * 1024);

            // Add your WebSocket endpoint(s) to the JettyWebSocketServerContainer.
            container.addMapping("/ws/myURI", MyJettyWebSocketEndPoint.class);

            // Use JettyWebSocketCreator to have more control on the WebSocket endpoint creation.
            container.addMapping("/ws/myOtherURI", (upgradeRequest, upgradeResponse) ->
            {
                // Possibly inspect the upgrade request and modify the upgrade response.
                upgradeResponse.setAcceptedSubProtocol("my-ws-protocol");

                // Create the new WebSocket endpoint.
                return new MyOtherJettyWebSocketEndPoint();
            });
        });

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::jettyContainerAndEndpoints[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::jettyContainerUpgrade[]
    public class ProgrammaticWebSocketUpgradeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            if (requiresWebSocketUpgrade(request))
            {
                // Retrieve the JettyWebSocketServerContainer.
                JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(getServletContext());

                // Use a JettyWebSocketCreator to inspect the upgrade request,
                // possibly modify the upgrade response, and create the WebSocket endpoint.
                JettyWebSocketCreator creator = (upgradeRequest, upgradeResponse) -> new MyJettyWebSocketEndPoint();

                // Perform the direct WebSocket upgrade.
                container.upgrade(creator, request, response);
            }
            else
            {
                // Normal handling of the HTTP request/response.
            }
        }
    }
    // end::jettyContainerUpgrade[]

    private boolean requiresWebSocketUpgrade(HttpServletRequest request)
    {
        return false;
    }

    public void jettyWebSocketServletMain() throws Exception
    {
        // tag::jettyWebSocketServletMain[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ServletContextHandler with the given context path.
        ServletContextHandler handler = new ServletContextHandler(server, "/ctx");
        server.setHandler(handler);

        // Setup the JettyWebSocketServerContainer to initialize WebSocket components.
        JettyWebSocketServletContainerInitializer.configure(handler, null);

        // Add your WebSocketServlet subclass to the ServletContextHandler.
        handler.addServlet(MyJettyWebSocketServlet.class, "/ws/*");

        // Starting the Server will start the ServletContextHandler.
        server.start();
        // end::jettyWebSocketServletMain[]
    }

    @SuppressWarnings("InnerClassMayBeStatic")
    // tag::jettyWebSocketServlet[]
    public class MyJettyWebSocketServlet extends JettyWebSocketServlet
    {
        @Override
        protected void configure(JettyWebSocketServletFactory factory)
        {
            // At most 1 MiB text messages.
            factory.setMaxTextMessageSize(1048576);

            // Add the WebSocket endpoint.
            factory.addMapping("/ws/someURI", (upgradeRequest, upgradeResponse) ->
            {
                // Possibly inspect the upgrade request and modify the upgrade response.

                // Create the new WebSocket endpoint.
                return new MyJettyWebSocketEndPoint();
            });
        }
    }
    // end::jettyWebSocketServlet[]

    @ServerEndpoint("/ws")
    private static class MyJavaxWebSocketEndPoint
    {
    }

    @WebSocket
    private static class MyJettyWebSocketEndPoint
    {
    }

    @WebSocket
    private static class MyOtherJettyWebSocketEndPoint
    {
    }
}
