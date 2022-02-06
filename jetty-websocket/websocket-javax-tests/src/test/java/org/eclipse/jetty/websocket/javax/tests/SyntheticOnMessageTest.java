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

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SyntheticOnMessageTest
{
    private Server server;
    private URI serverUri;
    private ServerConnector connector;
    private WebSocketContainer client;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(ServerSocket.class));
        server.setHandler(contextHandler);
        server.start();
        serverUri = URI.create("ws://localhost:" + connector.getLocalPort());
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void after() throws Exception
    {
        LifeCycle.stop(client);
        server.stop();
    }

    public static class AnnotatedEndpoint<T>
    {
        public void onMessage(T message)
        {
        }
    }

    @ServerEndpoint("/")
    public static class ServerSocket extends AnnotatedEndpoint<String>
    {
        @OnMessage
        public void onMessage(String message)
        {
        }
    }

    @Test
    public void syntheticOnMessageTest() throws Exception
    {
        // ServerSocket has two annotated onMessage methods, one is a synthetic bridge method generated
        // by the compiler and shouldn't be used.
        List<Method> annotatedOnMessages = Stream.of(ServerSocket.class.getDeclaredMethods())
            .filter(method -> method.getAnnotation(OnMessage.class) != null)
            .collect(Collectors.toList());
        assertThat(annotatedOnMessages.size(), is(2));

        // We should correctly filter out all synthetic methods so we should not get an InvalidSignatureException.
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, serverUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
    }
}
