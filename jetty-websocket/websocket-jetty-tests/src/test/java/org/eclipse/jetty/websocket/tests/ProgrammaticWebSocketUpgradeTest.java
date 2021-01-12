//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketCreator;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProgrammaticWebSocketUpgradeTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private ServletContextHandler contextHandler;

    @BeforeEach
    public void before() throws Exception
    {
        client = new WebSocketClient();
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new CustomUpgradeServlet()), "/");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler, null);

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    public static class CustomUpgradeServlet extends HttpServlet
    {
        private final Handshaker handshaker = Handshaker.newInstance();
        private FrameHandlerFactory frameHandlerFactory;
        private WebSocketComponents components;

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            ServletContext servletContext = getServletContext();
            JettyWebSocketServerContainer container = JettyWebSocketServerContainer.getContainer(servletContext);
            components = WebSocketServerComponents.getWebSocketComponents(servletContext);
            frameHandlerFactory = container.getBean(FrameHandlerFactory.class);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            WebSocketCreator creator = (req, resp) -> new EchoSocket();
            WebSocketNegotiator negotiator = WebSocketNegotiator.from(creator, frameHandlerFactory);
            handshaker.upgradeRequest(negotiator, request, response, components, null);
        }
    }

    @Test
    public void testProgrammaticWebSocketUpgrade() throws Exception
    {
        // Test we can upgrade to websocket and send a message.
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/path");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
        }
        assertTrue(socket.closeLatch.await(10, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));

        // WebSocketUpgradeFilter should not have been added.
        assertThat(contextHandler.getServletHandler().getFilters().length, is(0));
    }
}
