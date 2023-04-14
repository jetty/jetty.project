//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests.listeners;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketListenerTest
{
    private Server server;
    private URI serverUri;
    private WebSocketClient client;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
        {
            container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket());
            for (Class<?> c : getClassListFromArguments(TextListeners.getTextListeners()))
            {
                container.addMapping("/text/" + c.getSimpleName(), (rq, rs, cb) -> construct(c));
            }
            for (Class<?> c : getClassListFromArguments(BinaryListeners.getBinaryListeners()))
            {
                container.addMapping("/binary/" + c.getSimpleName(), (rq, rs, cb) -> construct(c));
            }
        });

        server.setHandler(context);
        server.start();
        serverUri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        client.stop();
        server.stop();
    }

    @ParameterizedTest
    @MethodSource("org.eclipse.jetty.websocket.tests.listeners.TextListeners#getTextListeners")
    public void testTextListeners(Class<?> clazz) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        client.connect(clientEndpoint, serverUri.resolve("/text/" + clazz.getSimpleName())).get(5, TimeUnit.SECONDS);

        // Send and receive echo on client.
        String payload = "hello world";
        clientEndpoint.session.sendText(payload, Callback.NOOP);
        String echoMessage = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(echoMessage, is(payload));

        // Close normally.
        clientEndpoint.session.close(StatusCode.NORMAL, "standard close", Callback.NOOP);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("standard close"));
    }

    @ParameterizedTest
    @MethodSource("org.eclipse.jetty.websocket.tests.listeners.BinaryListeners#getBinaryListeners")
    public void testBinaryListeners(Class<?> clazz) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        client.connect(clientEndpoint, serverUri.resolve("/binary/" + clazz.getSimpleName())).get(5, TimeUnit.SECONDS);

        // Send and receive echo on client.
        ByteBuffer payload = BufferUtil.toBuffer("hello world");
        clientEndpoint.session.sendBinary(payload, Callback.NOOP);
        ByteBuffer echoMessage = clientEndpoint.binaryMessages.poll(5, TimeUnit.SECONDS);
        assertThat(echoMessage, is(payload));

        // Close normally.
        clientEndpoint.session.close(StatusCode.NORMAL, "standard close", Callback.NOOP);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, is(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, is("standard close"));
    }

    @Test
    public void testAnonymousListener() throws Exception
    {
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
        Session.Listener clientEndpoint = new Session.Listener()
        {
            @Override
            public void onWebSocketConnect(Session session)
            {
                openLatch.countDown();
            }

            @Override
            public void onWebSocketText(String message)
            {
                textMessages.add(message);
            }

            @Override
            public void onWebSocketClose(int statusCode, String reason)
            {
                closeLatch.countDown();
            }
        };

        Session session = client.connect(clientEndpoint, serverUri.resolve("/echo")).get(5, TimeUnit.SECONDS);
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));

        // Send and receive echo on client.
        String payload = "hello world";
        session.sendText(payload, Callback.NOOP);
        String echoMessage = textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(echoMessage, is(payload));

        // Close normally.
        session.close(StatusCode.NORMAL, "standard close", Callback.NOOP);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    private List<Class<?>> getClassListFromArguments(Stream<Arguments> stream)
    {
        return stream.map(arguments -> (Class<?>)arguments.get()[0]).collect(Collectors.toList());
    }

    private <T> T construct(Class<T> clazz)
    {
        try
        {
            @SuppressWarnings("unchecked")
            T instance = (T)clazz.getConstructors()[0].newInstance();
            return instance;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
}
