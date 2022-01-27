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

package org.eclipse.jetty.websocket.jakarta.tests.server;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jakarta.common.decoders.StringDecoder;
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.websocket.jakarta.tests.WSEndpointTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example of an annotated echo server discovered via annotation scanning.
 */
public class ServerDecoderTest
{
    private static CompletableFuture<EventSocket> annotatedServerSocket = new CompletableFuture<>();
    private static CompletableFuture<WSEndpointTracker> configuredServerSocket = new CompletableFuture<>();

    private Server server;
    private URI serverURI;

    public static class EqualsAppendDecoder extends StringDecoder
    {
        @Override
        public String decode(String s)
        {
            return s + "=";
        }
    }

    public static class PlusAppendDecoder extends StringDecoder
    {
        @Override
        public String decode(String s)
        {
            return s + "+";
        }
    }

    @ServerEndpoint(value = "/annotated", decoders = {EqualsAppendDecoder.class})
    public static class AnnotatedEndpoint extends EventSocket
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            super.onOpen(session, config);
            annotatedServerSocket.complete(this);
        }
    }

    public static class ConfiguredEndpoint extends WSEndpointTracker implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            super.onOpen(session, config);
            session.addMessageHandler(this);
            configuredServerSocket.complete(this);
        }

        @Override
        public void onMessage(String message)
        {
            super.onWsText(message);
        }
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);
        ServletContextHandler servletContextHandler = new ServletContextHandler(null, "/");
        server.setHandler(servletContextHandler);

        JakartaWebSocketServletContainerInitializer.configure(servletContextHandler, ((servletContext, serverContainer) ->
        {
            serverContainer.addEndpoint(AnnotatedEndpoint.class);

            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ConfiguredEndpoint.class, "/configured")
                .decoders(Collections.singletonList(PlusAppendDecoder.class))
                .build();
            serverContainer.addEndpoint(config);
        }));

        server.start();
        serverURI = new URI("ws://localhost:" + serverConnector.getLocalPort());
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testAnnotatedDecoder() throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, serverURI.resolve("/annotated"));
        session.getBasicRemote().sendText("hello world");

        EventSocket serverSocket = annotatedServerSocket.get(5, TimeUnit.SECONDS);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        String msg = serverSocket.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world="));

        clientSocket.session.close();
        clientSocket.closeLatch.await(5, TimeUnit.SECONDS);
        serverSocket.closeLatch.await(5, TimeUnit.SECONDS);
    }

    @Test
    public void testConfiguredDecoder() throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, serverURI.resolve("/configured"));
        session.getBasicRemote().sendText("hello world");

        WSEndpointTracker serverSocket = configuredServerSocket.get(5, TimeUnit.SECONDS);
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));
        String msg = serverSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg, is("hello world+"));

        clientSocket.session.close();
        clientSocket.closeLatch.await(5, TimeUnit.SECONDS);
        serverSocket.closeLatch.await(5, TimeUnit.SECONDS);
    }
}
