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

package org.eclipse.jetty.websocket.jakarta.tests;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.jakarta.server.internal.JakartaWebSocketServerContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RestartContextTest
{
    private Server server;

    @AfterEach
    public void stopServer() throws Exception
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
        JakartaWebSocketServletContainerInitializer.configure(context, null);
        // late initialization via my own ServletContextListener
        context.addEventListener(new AddEndpointListener());

        // Setup handler tree
        server.setHandler(new HandlerList(context, new DefaultHandler()));

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
        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, serverContainer) ->
        {
            // Add endpoint via configurator
            serverContainer.addEndpoint(EchoEndpoint.class);
        });

        // Setup handler tree
        HandlerList handlers = new HandlerList(context, new DefaultHandler());

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

    private void verifyWebSocketEcho(URI endpointUri) throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        ClientSocket endpoint = new ClientSocket();
        try (Session session = container.connectToServer(endpoint, WSURI.toWebsocket(endpointUri)))
        {
            session.getBasicRemote().sendText("Test Echo");
            String msg = endpoint.messages.poll(5, TimeUnit.SECONDS);
            assertThat("msg", msg, is("Test Echo"));
        }
    }

    public static class AddEndpointListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            ServerContainer container = JakartaWebSocketServerContainer.getContainer(sce.getServletContext());
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

    @ClientEndpoint
    public static class ClientSocket
    {
        public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnMessage
        public void onMessage(String msg)
        {
            this.messages.offer(msg);
        }
    }
}
