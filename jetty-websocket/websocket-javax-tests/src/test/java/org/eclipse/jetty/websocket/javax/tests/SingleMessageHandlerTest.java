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
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SingleMessageHandlerTest
{
    private static final LinkedBlockingQueue<ByteBuffer> BINARY_MESSAGES = new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<String> TEXT_MESSAGES = new LinkedBlockingQueue<>();

    private Server _server;
    private ServerConnector _connector;
    private JavaxWebSocketClientContainer _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, serverContainer) ->
        {
            serverContainer.addEndpoint(TextEndpoint.class);
            serverContainer.addEndpoint(BinaryEndpoint.class);
        }));
        _server.setHandler(contextHandler);

        _server.start();
        _client = new JavaxWebSocketClientContainer();
        _client.start();
    }

    @ServerEndpoint("/binary")
    public static class BinaryEndpoint
    {
        @OnMessage
        public void onMessage(ByteBuffer message)
        {
            BINARY_MESSAGES.add(message);
        }
    }

    @ServerEndpoint("/text")
    public static class TextEndpoint
    {
        @OnMessage
        public void onMessage(String message)
        {
            TEXT_MESSAGES.add(message);
        }
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testBinary() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/binary");
        EventSocket eventSocket = new EventSocket();
        Session session = _client.connectToServer(eventSocket, uri);

        // Can send/receive binary message successfully.
        ByteBuffer binaryMessage = BufferUtil.toBuffer("hello world");
        session.getBasicRemote().sendBinary(binaryMessage);
        assertThat(BINARY_MESSAGES.poll(5, TimeUnit.SECONDS), equalTo(binaryMessage));

        // Text message is discarded by implementation.
        session.getBasicRemote().sendText("hello world");

        // Next binary message is still received.
        session.getBasicRemote().sendBinary(binaryMessage);
        assertThat(BINARY_MESSAGES.poll(5, TimeUnit.SECONDS), equalTo(binaryMessage));

        session.close();
        assertTrue(eventSocket.closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testText() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "/text");
        EventSocket eventSocket = new EventSocket();
        Session session = _client.connectToServer(eventSocket, uri);

        // Can send/receive text message successfully.
        String textMessage = "hello world";
        session.getBasicRemote().sendText(textMessage);
        assertThat(TEXT_MESSAGES.poll(5, TimeUnit.SECONDS), equalTo(textMessage));

        // Binary message is discarded by implementation.
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer("hello world"));

        // Next text message is still received.
        session.getBasicRemote().sendText(textMessage);
        assertThat(TEXT_MESSAGES.poll(5, TimeUnit.SECONDS), equalTo(textMessage));

        session.close();
        assertTrue(eventSocket.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
