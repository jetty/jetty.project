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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.util.TextUtils;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PartialListenerTest
{
    private Server server;
    private PartialEndpoint serverEndpoint;
    private WebSocketClient client;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
        {
            container.setIdleTimeout(Duration.ofSeconds(2));
            container.addMapping("/ws", (rq, rs, cb) -> serverEndpoint = new PartialEndpoint());
        });

        server.setHandler(context);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    private void close(Session session)
    {
        if (session != null)
        {
            session.close();
        }
    }

    @Test
    public void testPartialText() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            session.sendPartialText("hello", false, Callback.NOOP);
            session.sendPartialText(" ", false, Callback.NOOP);
            session.sendPartialText("world", true, Callback.NOOP);

            String event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=hello, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload= , fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=world, fin=true]"));
        }
        finally
        {
            close(session);
        }
    }

    @Test
    public void testPartialBinary() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            session.sendPartialBinary(BufferUtil.toBuffer("hello"), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.toBuffer(" "), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.toBuffer("world"), true, Callback.NOOP);

            String event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<hello>>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<< >>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<world>>>, fin=true]"));
        }
        finally
        {
            close(session);
        }
    }

    /**
     * Test to ensure that the internal state tracking the partial messages is reset after each complete message.
     */
    @Test
    public void testPartialTextBinaryText() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            session.sendPartialText("hello", false, Callback.NOOP);
            session.sendPartialText(" ", false, Callback.NOOP);
            session.sendPartialText("world", true, Callback.NOOP);

            session.sendPartialBinary(BufferUtil.toBuffer("greetings"), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.toBuffer(" "), false, Callback.NOOP);
            session.sendPartialBinary(BufferUtil.toBuffer("mars"), true, Callback.NOOP);

            session.sendPartialText("salutations", false, Callback.NOOP);
            session.sendPartialText(" ", false, Callback.NOOP);
            session.sendPartialText("phobos", true, Callback.NOOP);

            String event;
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=hello, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload= , fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=world, fin=true]"));

            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<greetings>>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<< >>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<mars>>>, fin=true]"));

            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=salutations, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload= , fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=phobos, fin=true]"));
        }
        finally
        {
            close(session);
        }
    }

    public static class PartialEndpoint implements Session.Listener
    {
        public Session session;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public LinkedBlockingQueue<String> partialEvents = new LinkedBlockingQueue<>();

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            session.demand();
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            partialEvents.offer(String.format("TEXT[payload=%s, fin=%b]", TextUtils.maxStringLength(30, payload), fin));
            session.demand();
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            // our testcases always send bytes limited in the US-ASCII range.
            partialEvents.offer(String.format("BINARY[payload=<<<%s>>>, fin=%b]", BufferUtil.toUTF8String(payload), fin));
            callback.succeed();
            session.demand();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }
    }
}
