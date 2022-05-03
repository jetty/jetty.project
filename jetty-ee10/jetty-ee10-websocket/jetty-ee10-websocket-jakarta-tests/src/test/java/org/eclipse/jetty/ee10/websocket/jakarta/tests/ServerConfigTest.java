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
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerConfigTest
{
    private Server server;
    private WebSocketContainer client;
    private ServerConnector connector;

    private static final long idleTimeout = 500;
    private static final int maxTextMessageSize = 50;
    private static final int maxBinaryMessageSize = 60;
    private static final long asyncSendTimeout = 200;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.setDefaultMaxSessionIdleTimeout(idleTimeout);
            container.setDefaultMaxTextMessageBufferSize(maxTextMessageSize);
            container.setDefaultMaxBinaryMessageBufferSize(maxBinaryMessageSize);
            container.setAsyncSendTimeout(asyncSendTimeout);
            container.addEndpoint(ConfigTestSocket.class);
            container.addEndpoint(AnnotatedOnMessageSocket.class);
        });

        server.start();
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
    }

    @ServerEndpoint("/containerDefaults")
    public static class ConfigTestSocket
    {
        @OnOpen
        public void onOpen(Session session)
        {
            assertThat(session.getMaxIdleTimeout(), is(idleTimeout));
            assertThat(session.getMaxTextMessageBufferSize(), is(maxTextMessageSize));
            assertThat(session.getMaxBinaryMessageBufferSize(), is(maxBinaryMessageSize));
            assertThat(session.getAsyncRemote().getSendTimeout(), is(asyncSendTimeout));
        }
    }

    @ServerEndpoint("/annotatedOnMessage")
    public static class AnnotatedOnMessageSocket
    {
        @OnOpen
        public void onOpen(Session session)
        {
            assertThat(session.getMaxTextMessageBufferSize(), is(111));
            assertThat(session.getMaxBinaryMessageBufferSize(), is(maxBinaryMessageSize));
        }

        @OnMessage(maxMessageSize = 111)
        public void onMessage(String message) throws IOException
        {
        }

        @OnMessage()
        public void onMessage(ByteBuffer message) throws IOException
        {
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/containerDefaults", "/annotatedOnMessage"})
    public void testEndpointSettings(String path) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + path);
        EventSocket clientEndpoint = new EventSocket();
        client.connectToServer(clientEndpoint, uri);

        clientEndpoint.openLatch.await(5, TimeUnit.SECONDS);
        clientEndpoint.session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "normal close"));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseCodes.NORMAL_CLOSURE));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("normal close"));
    }
}
