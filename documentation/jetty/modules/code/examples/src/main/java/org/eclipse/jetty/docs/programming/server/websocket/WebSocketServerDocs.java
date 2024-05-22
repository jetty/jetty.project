//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

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
        ServletContextHandler handler = new ServletContextHandler("/ctx");
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
        ServletContextHandler handler = new ServletContextHandler("/ctx");
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
        ServletContextHandler handler = new ServletContextHandler("/ctx");
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

    public void jettyContainerWithUpgradeHandler() throws Exception
    {
        // tag::jettyContainerWithUpgradeHandler[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ContextHandler with the given context path.
        ContextHandler contextHandler = new ContextHandler("/ctx");
        server.setHandler(contextHandler);

        // Create a WebSocketUpgradeHandler that implicitly creates a ServerWebSocketContainer.
        WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(server, contextHandler, container ->
        {
            // Configure the ServerWebSocketContainer.
            container.setMaxTextMessageSize(128 * 1024);

            // Map a request URI to a WebSocket endpoint, for example using a regexp.
            container.addMapping("regex|/ws/v\\d+/echo", (rq, rs, cb) -> new EchoEndPoint());

            // Advanced registration of a WebSocket endpoint.
            container.addMapping("/ws/adv", (rq, rs, cb) ->
            {
                List<String> subProtocols = rq.getSubProtocols();
                if (subProtocols.contains("my-ws-protocol"))
                    return new MyJettyWebSocketEndPoint();
                return null;
            });
        });
        contextHandler.setHandler(webSocketHandler);

        // Starting the Server will start the ContextHandler and the WebSocketUpgradeHandler,
        // which would run the configuration of the ServerWebSocketContainer.
        server.start();
        // end::jettyContainerWithUpgradeHandler[]
    }

    private static class EchoEndPoint
    {
    }

    public void jettyContainerWithContainer() throws Exception
    {
        // tag::jettyContainerWithContainer[]
        // Create a Server with a ServerConnector listening on port 8080.
        Server server = new Server(8080);

        // Create a ContextHandler with the given context path.
        ContextHandler contextHandler = new ContextHandler("/ctx");
        server.setHandler(contextHandler);

        // Create a ServerWebSocketContainer, which is also stored as an attribute in the context.
        ServerWebSocketContainer container = ServerWebSocketContainer.ensure(server, contextHandler);

        // You can use WebSocketUpgradeHandler if you want, but it is not necessary.
        // You can ignore the line below, it is shown only for reference.
        WebSocketUpgradeHandler webSocketHandler = new WebSocketUpgradeHandler(container);

        // You can directly use ServerWebSocketContainer from any Handler.
        contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Retrieve the ServerWebSocketContainer.
                ServerWebSocketContainer container = ServerWebSocketContainer.get(request.getContext());

                // Verify special conditions for which a request should be upgraded to WebSocket.
                String pathInContext = Request.getPathInContext(request);
                if (pathInContext.startsWith("/ws/echo") && request.getHeaders().contains("X-WS", "true"))
                {
                    try
                    {
                        // This is a WebSocket upgrade request, perform a direct upgrade.
                        boolean upgraded = container.upgrade((rq, rs, cb) -> new EchoEndPoint(), request, response, callback);
                        if (upgraded)
                            return true;
                        // This was supposed to be a WebSocket upgrade request, but something went wrong.
                        Response.writeError(request, response, callback, HttpStatus.UPGRADE_REQUIRED_426);
                        return true;
                    }
                    catch (Exception x)
                    {
                        Response.writeError(request, response, callback, HttpStatus.UPGRADE_REQUIRED_426, "failed to upgrade", x);
                        return true;
                    }
                }
                else
                {
                    // Handle a normal HTTP request.
                    response.setStatus(HttpStatus.OK_200);
                    callback.succeeded();
                    return true;
                }
            }
        });

        // Starting the Server will start the ContextHandler.
        server.start();
        // end::jettyContainerWithContainer[]
    }

    @ServerEndpoint("/ws")
    private static class MyJavaxWebSocketEndPoint
    {
    }

    @WebSocket
    private static class MyJettyWebSocketEndPoint
    {
    }

    public void uriTemplatePathSpec()
    {
        // tag::uriTemplatePathSpec[]
        Server server = new Server(8080);

        ContextHandler contextHandler = new ContextHandler("/ctx");
        server.setHandler(contextHandler);

        // Create a WebSocketUpgradeHandler.
        WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(server, contextHandler, container ->
        {
            container.addMapping("/ws/chat/{room}", (upgradeRequest, upgradeResponse, callback) ->
            {
                // Retrieve the URI template.
                UriTemplatePathSpec pathSpec = (UriTemplatePathSpec)upgradeRequest.getAttribute(PathSpec.class.getName());

                // Match the URI template.
                String pathInContext = Request.getPathInContext(upgradeRequest);
                Map<String, String> params = pathSpec.getPathParams(pathInContext);
                String room = params.get("room");

                // Create the new WebSocket endpoint with the URI template information.
                return new MyWebSocketRoomEndPoint(room);
            });
        });
        contextHandler.setHandler(webSocketHandler);
        // end::uriTemplatePathSpec[]
    }

    @WebSocket
    private static class MyWebSocketRoomEndPoint
    {
        public MyWebSocketRoomEndPoint(String room)
        {
        }
    }
}
