//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.eclipse.jetty.websocket.javax.tests.WSEndpointTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

/**
 * Tests of {@link javax.websocket.ClientEndpointConfig.Configurator}
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
