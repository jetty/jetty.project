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

package org.eclipse.jetty.ee9.websocket.jakarta.tests.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSEndpointTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

/**
 * Tests of {@link jakarta.websocket.ClientEndpointConfig.Configurator}
 */
public class ConfiguratorTest
{
    public class TrackingConfigurator extends ClientEndpointConfig.Configurator
    {
        public HandshakeResponse response;
        public Map<String, List<String>> request;

        @Override
        public void afterResponse(HandshakeResponse hr)
        {
            this.response = hr;
        }

        @Override
        public void beforeRequest(Map<String, List<String>> headers)
        {
            this.request = headers;
        }
    }

    public class ClientEndpointTracker extends WSEndpointTracker implements MessageHandler.Whole<String>
    {
        public ClientEndpointTracker()
        {
            super("@ClientEndpointTracker");
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            super.onOpen(session, config);
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
            super.onWsText(message);
        }
    }

    @ServerEndpoint("/echo")
    public static class EchoSocket
    {
        @OnMessage
        public String onMessage(String msg)
        {
            return msg;
        }
    }

    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(EchoSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testEndpointHandshakeInfo() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        ClientEndpointTracker clientSocket = new ClientEndpointTracker();

        // Build Config
        TrackingConfigurator configurator = new TrackingConfigurator();
        ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
            .configurator(configurator)
            .build();

        // Connect
        Session session = container.connectToServer(clientSocket, config, server.getWsUri().resolve("/echo"));

        // Send Simple Message
        session.getBasicRemote().sendText("Echo");

        // Wait for echo
        String echoed = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Echoed Message", echoed, is("Echo"));

        // Validate client side configurator use
        assertThat("configurator.request", configurator.request, notNullValue());
        assertThat("configurator.response", configurator.response, notNullValue());

        session.close();
    }
}
