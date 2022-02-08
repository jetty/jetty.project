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

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GracefulCloseTest
{
    private static final BlockingArrayQueue<EventSocket> serverEndpoints = new BlockingArrayQueue<>();
    private Server server;
    private URI serverUri;
    private JavaxWebSocketClientContainer client;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(ServerSocket.class));
        server.start();
        serverUri = WSURI.toWebsocket(server.getURI());

        // StopTimeout is necessary for the websocket server sessions to gracefully close.
        server.setStopTimeout(1000);

        client = new JavaxWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        client.stop();
        server.stop();
    }

    @ServerEndpoint("/")
    public static class ServerSocket extends EchoSocket
    {
        @Override
        public void onOpen(Session session, EndpointConfig endpointConfig)
        {
            serverEndpoints.add(this);
            super.onOpen(session, endpointConfig);
        }
    }

    @Test
    public void testClientStop() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        client.connectToServer(clientEndpoint, serverUri);
        EventSocket serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));

        // There is no API for a Javax WebSocketContainer stop timeout.
        Graceful.shutdown(client).get(5, TimeUnit.SECONDS);
        client.stop();

        // Check that the client endpoint was closed with the correct status code and no error.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseReason.CloseCodes.GOING_AWAY));
        assertNull(clientEndpoint.error);

        // Check that the server endpoint was closed with the correct status code and no error.
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.closeReason.getCloseCode(), is(CloseReason.CloseCodes.GOING_AWAY));
        assertNull(serverEndpoint.error);
    }

    @Test
    public void testServerStop() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        client.connectToServer(clientEndpoint, serverUri);
        EventSocket serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));

        server.stop();

        // Check that the client endpoint was closed with the correct status code and no error.
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(CloseReason.CloseCodes.GOING_AWAY));
        assertNull(clientEndpoint.error);

        // Check that the server endpoint was closed with the correct status code and no error.
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverEndpoint.closeReason.getCloseCode(), is(CloseReason.CloseCodes.GOING_AWAY));
        assertNull(serverEndpoint.error);
    }
}
