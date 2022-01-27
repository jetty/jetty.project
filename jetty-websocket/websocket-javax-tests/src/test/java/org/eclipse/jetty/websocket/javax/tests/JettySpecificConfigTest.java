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

package org.eclipse.jetty.websocket.javax.tests;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettySpecificConfigTest
{
    private Server _server;
    private ServerConnector _connector;
    private ServletContextHandler _context;

    @ServerEndpoint(value = "/", configurator = JettyServerConfigurator.class)
    public static class EchoParamSocket
    {
        private Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;

            JavaxWebSocketSession javaxSession = (JavaxWebSocketSession)session;
            assertThat(javaxSession.getCoreSession().isAutoFragment(), is(false));
            assertThat(javaxSession.getCoreSession().getMaxFrameSize(), is(1337L));
        }

        @OnMessage
        public void onMessage(String message) throws IOException
        {
            session.getBasicRemote().sendText(message);
        }
    }

    public static class JettyServerConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            Map<String, Object> userProperties = sec.getUserProperties();
            userProperties.put("org.eclipse.jetty.websocket.autoFragment", false);
            userProperties.put("org.eclipse.jetty.websocket.maxFrameSize", 1337L);
        }
    }

    @ClientEndpoint
    public static class ClientConfigSocket extends EventSocket
    {
        @Override
        public void onOpen(Session session, EndpointConfig endpointConfig)
        {
            super.onOpen(session, endpointConfig);
            Map<String, Object> userProperties = session.getUserProperties();
            userProperties.put("org.eclipse.jetty.websocket.autoFragment", false);
            userProperties.put("org.eclipse.jetty.websocket.maxFrameSize", 1337L);
        }
    }

    @BeforeEach
    public void startContainer() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/");
        _server.setHandler(_context);

        JavaxWebSocketServletContainerInitializer.configure(_context,
            (context, container) -> container.addEndpoint(EchoParamSocket.class));

        _server.start();
    }

    @AfterEach
    public void stopContainer() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testJettySpecificConfig() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new ClientConfigSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort());
        Session session = container.connectToServer(clientEndpoint, serverUri);

        // Check correct client config is set.
        JavaxWebSocketSession javaxSession = (JavaxWebSocketSession)session;
        assertThat(javaxSession.getCoreSession().isAutoFragment(), is(false));
        assertThat(javaxSession.getCoreSession().getMaxFrameSize(), is(1337L));

        // Send and receive an echo.
        session.getBasicRemote().sendText("echo");
        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("echo"));

        // Close the Session.
        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
        assertNull(clientEndpoint.error);
    }
}
