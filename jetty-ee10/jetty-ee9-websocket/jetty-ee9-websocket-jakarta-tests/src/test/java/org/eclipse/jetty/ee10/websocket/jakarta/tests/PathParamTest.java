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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.jakarta.server.internal.JakartaWebSocketServerContainer;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.BooleanClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.BooleanTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.ByteClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.ByteTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.CharacterClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.CharacterTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.DoubleClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.DoubleTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.FloatClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.FloatTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.IntegerClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.IntegerTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.LongClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.LongTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.ShortClassSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.ShortTypeSocket;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.pathparam.StringClassSocket;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathParamTest
{
    private Server _server;
    private ServerConnector _connector;
    private ServletContextHandler _context;

    @BeforeEach
    public void startContainer() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _context.setContextPath("/context");
        _server.setHandler(_context);

        JakartaWebSocketServletContainerInitializer.configure(_context, (context, container) ->
            container.addEndpoint(EchoParamSocket.class));

        _server.start();
    }

    @AfterEach
    public void stopContainer() throws Exception
    {
        _server.stop();
    }

    @ServerEndpoint("/pathParam/echo/{name}")
    public static class EchoParamSocket
    {
        private Session session;

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        @OnMessage
        public void onMessage(String message, @PathParam("name") String name)
        {
            session.getAsyncRemote().sendText(message + "-" + name);
        }
    }

    public static Stream<Arguments> pathParamEndpoints()
    {
        return Stream.of(
            Arguments.of(BooleanClassSocket.class, "false"),
            Arguments.of(BooleanTypeSocket.class, "true"),
            Arguments.of(ByteClassSocket.class, "32"),
            Arguments.of(ByteTypeSocket.class, "51"),
            Arguments.of(CharacterClassSocket.class, "q"),
            Arguments.of(CharacterTypeSocket.class, "&"),
            Arguments.of(DoubleClassSocket.class, Double.toString(Double.MAX_VALUE)),
            Arguments.of(DoubleTypeSocket.class, Double.toString(Double.MIN_VALUE)),
            Arguments.of(FloatClassSocket.class, "0.00235"),
            Arguments.of(FloatTypeSocket.class, "123.456"),
            Arguments.of(IntegerClassSocket.class, Integer.toString(Integer.MIN_VALUE)),
            Arguments.of(IntegerTypeSocket.class, Integer.toString(Integer.MAX_VALUE)),
            Arguments.of(LongClassSocket.class, Long.toString(Long.MAX_VALUE)),
            Arguments.of(LongTypeSocket.class, Long.toString(Long.MIN_VALUE)),
            Arguments.of(ShortClassSocket.class, Short.toString(Short.MAX_VALUE)),
            Arguments.of(ShortTypeSocket.class, Short.toString(Short.MIN_VALUE)),
            Arguments.of(StringClassSocket.class, "this_is_a_String_ID")
        );
    }

    @ParameterizedTest
    @MethodSource("pathParamEndpoints")
    public void testPathParamSignatures(Class<?> endpointClass, String id) throws Exception
    {
        JakartaWebSocketServerContainer serverContainer = JakartaWebSocketServerContainer.getContainer(_context.getServletContext());
        assertNotNull(serverContainer);
        serverContainer.addEndpoint(endpointClass);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/id/" + id);
        container.connectToServer(clientEndpoint, serverUri);

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat(resp, is("id: " + id));
    }

    @Test
    public void testBasicPathParamSocket() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        EventSocket clientEndpoint = new EventSocket();

        URI serverUri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/context/pathParam/echo/myParam");
        Session session = container.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("echo");

        String resp = clientEndpoint.textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("echo-myParam"));
        session.close();
        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }
}
