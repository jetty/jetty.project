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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.OnMessage;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RestartContextTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void startServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testStartStopStartServletContextListener() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Setup Context
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        WebSocketServerContainerInitializer.configure(context, null);
        // late initialization via my own ServletContextListener
        context.addEventListener(new AddEndpointListener());

        // Setup handler tree
        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        // Add handler tree to server
        server.setHandler(handlers);

        // Start server
        server.start();

        // verify functionality
        verifyWebSocketEcho(server.getURI().resolve("/echo"));

        // Stop server
        server.stop();

        // Start server (again)
        server.start();

        // verify functionality (again)
        verifyWebSocketEcho(server.getURI().resolve("/echo"));
    }

    @Test
    public void testStartStopStartConfigurator() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Setup Context
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        WebSocketServerContainerInitializer.configure(context, (servletContext, serverContainer) ->
        {
            // Add endpoint via configurator
            serverContainer.addEndpoint(EchoEndpoint.class);
        });

        // Setup handler tree
        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        // Add handler tree to server
        server.setHandler(handlers);

        // Start server
        server.start();

        // verify functionality
        verifyWebSocketEcho(server.getURI().resolve("/echo"));

        // Stop server
        server.stop();

        // Start server (again)
        server.start();

        // verify functionality (again)
        verifyWebSocketEcho(server.getURI().resolve("/echo"));
    }

    private void verifyWebSocketEcho(URI endpointUri) throws URISyntaxException, IOException, ExecutionException, InterruptedException
    {
        ClientEndpoint endpoint = new ClientEndpoint();
        Future<Session> fut = client.connect(endpoint, WSURI.toWebsocket(endpointUri));
        try (Session session = fut.get())
        {
            session.getRemote().sendString("Test Echo");
            String msg = endpoint.messages.poll(5, TimeUnit.SECONDS);
            assertThat("msg", msg, is("Test Echo"));
        }
    }

    public static class AddEndpointListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            ServerContainer container = (ServerContainer)sce.getServletContext().getAttribute(javax.websocket.server.ServerContainer.class.getName());
            try
            {
                container.addEndpoint(EchoEndpoint.class);
            }
            catch (DeploymentException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
        }
    }

    @ServerEndpoint("/echo")
    public static class EchoEndpoint
    {
        @OnMessage
        public String onMessage(String msg)
        {
            return msg;
        }
    }

    @WebSocket
    public static class ClientEndpoint
    {
        public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnWebSocketMessage
        public void onMessage(String msg)
        {
            this.messages.offer(msg);
        }
    }
}
