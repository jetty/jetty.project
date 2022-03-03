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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.handlers;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static jakarta.websocket.CloseReason.CloseCodes.NORMAL_CLOSURE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageHandlerTest
{
    private Server server;
    private URI serverUri;
    private WebSocketContainer client;

    private static Stream<Arguments> getBinaryHandlers()
    {
        return Stream.concat(BinaryHandlers.getBinaryHandlers(),
            Stream.of(ComboMessageHandler.class, ExtendedMessageHandler.class).map(Arguments::of));
    }

    private static Stream<Arguments> getTextHandlers()
    {
        return Stream.concat(TextHandlers.getTextHandlers(),
            Stream.of(ComboMessageHandler.class, ExtendedMessageHandler.class).map(Arguments::of));
    }

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            Stream<Arguments> argumentsStream = Stream.concat(getBinaryHandlers(), getTextHandlers());
            for (Class<?> c : getClassListFromArguments(argumentsStream))
            {
                container.addEndpoint(ServerEndpointConfig.Builder.create(c, "/" + c.getSimpleName()).build());
            }

            container.addEndpoint(ServerEndpointConfig.Builder.create(LongMessageHandler.class,
                "/" + LongMessageHandler.class.getSimpleName()).build());
        });

        server.setHandler(contextHandler);
        server.start();
        serverUri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void after() throws Exception
    {
        LifeCycle.stop(client);
        server.stop();
    }

    @ParameterizedTest
    @MethodSource("getBinaryHandlers")
    public void testBinaryHandlers(Class<?> clazz) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri.resolve(clazz.getSimpleName()));

        // Send and receive echo on client.
        ByteBuffer payload = BufferUtil.toBuffer("hello world");
        session.getBasicRemote().sendBinary(payload);
        ByteBuffer echoMessage = clientEndpoint.binaryMessages.poll(5, TimeUnit.SECONDS);
        assertThat(echoMessage, is(payload));

        // Close normally.
        session.close(new CloseReason(NORMAL_CLOSURE, "standard close"));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(NORMAL_CLOSURE));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("standard close"));
    }

    @ParameterizedTest
    @MethodSource("getTextHandlers")
    public void testTextHandlers(Class<?> clazz) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri.resolve(clazz.getSimpleName()));

        // Send and receive echo on client.
        String payload = "hello world";
        session.getBasicRemote().sendText(payload);
        String echoMessage = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(echoMessage, is(payload));

        // Close normally.
        session.close(new CloseReason(NORMAL_CLOSURE, "standard close"));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(NORMAL_CLOSURE));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("standard close"));
    }

    @Test
    public void testLongDecoderHandler() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri.resolve(LongMessageHandler.class.getSimpleName()));

        // Send and receive echo on client.
        String payload = Long.toString(Long.MAX_VALUE);
        session.getBasicRemote().sendText(payload);
        String echoMessage = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(echoMessage, is(payload));

        // Close normally.
        session.close(new CloseReason(NORMAL_CLOSURE, "standard close"));
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeReason.getCloseCode(), is(NORMAL_CLOSURE));
        assertThat(clientEndpoint.closeReason.getReasonPhrase(), is("standard close"));
    }

    private List<Class<?>> getClassListFromArguments(Stream<Arguments> stream)
    {
        return stream.map(arguments -> (Class<?>)arguments.get()[0]).collect(Collectors.toList());
    }
}
