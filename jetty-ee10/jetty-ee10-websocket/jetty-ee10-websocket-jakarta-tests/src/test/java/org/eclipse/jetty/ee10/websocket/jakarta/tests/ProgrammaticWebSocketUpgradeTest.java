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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.jakarta.server.internal.JakartaWebSocketServerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProgrammaticWebSocketUpgradeTest
{
    private static final Map<String, String> PATH_PARAMS = Map.of("param1", "value1", "param2", "value2");
    private static final JSON JSON = new JSON();
    private Server server;
    private ServerConnector connector;
    private JakartaWebSocketClientContainer client;

    @BeforeEach
    public void before() throws Exception
    {
        client = new JakartaWebSocketClientContainer();
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new CustomUpgradeServlet()), "/");
        server.setHandler(contextHandler);

        JakartaWebSocketServletContainerInitializer.configure(contextHandler, null);

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    public static class PathParamsEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            try
            {
                session.getBasicRemote().sendText(JSON.toJSON(session.getPathParameters()));
                session.close();
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static class CustomUpgradeServlet extends HttpServlet
    {
        private JakartaWebSocketServerContainer container;

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            container = JakartaWebSocketServerContainer.getContainer(getServletContext());
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            try
            {
                switch (request.getServletPath())
                {
                    case "/echo":
                    {
                        ServerEndpointConfig sec = ServerEndpointConfig.Builder.create(EchoSocket.class, "/").build();
                        HashMap<String, String> pathParams = new HashMap<>();
                        container.upgradeHttpToWebSocket(request, response, sec, pathParams);
                        break;
                    }
                    case "/pathParams":
                    {
                        ServerEndpointConfig sec = ServerEndpointConfig.Builder.create(PathParamsEndpoint.class, "/").build();
                        container.upgradeHttpToWebSocket(request, response, sec, PATH_PARAMS);
                        break;
                    }
                    default:
                        throw new IllegalStateException();
                }
            }
            catch (DeploymentException e)
            {
                throw new ServletException(e);
            }
        }
    }

    @Test
    public void testWebSocketUpgrade() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        EventSocket socket = new EventSocket();
        try (Session session = client.connectToServer(socket, uri))
        {
            session.getBasicRemote().sendText("hello world");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
        assertThat(socket.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }

    @Test
    public void testPathParameters() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/pathParams");
        EventSocket socket = new EventSocket();
        client.connectToServer(socket, uri);
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(JSON.fromJSON(msg), is(PATH_PARAMS));
        assertThat(socket.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }
}
