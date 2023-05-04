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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.exceptions.InvalidWebSocketException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AnnotatedPartialListenerTest
{
    public static class PartialEchoSocket implements Session.Listener.AutoDemanding
    {
        private Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin, Callback callback)
        {
            session.sendPartialBinary(payload, fin, callback);
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            session.sendPartialText(payload, fin, Callback.NOOP);
        }
    }

    @WebSocket
    public static class PartialStringListener
    {
        public BlockingQueue<MessageSegment> messages = new LinkedBlockingQueue<>();

        public static class MessageSegment
        {
            public String message;
            public boolean last;
        }

        @OnWebSocketMessage
        public void onMessage(String message, boolean last)
        {
            MessageSegment messageSegment = new MessageSegment();
            messageSegment.message = message;
            messageSegment.last = last;
            messages.add(messageSegment);
        }
    }

    @WebSocket
    public static class PartialByteBufferListener
    {
        public BlockingQueue<MessageSegment> messages = new LinkedBlockingQueue<>();

        public static class MessageSegment
        {
            public ByteBuffer buffer;
            public boolean last;
        }

        @OnWebSocketMessage
        public void onMessage(ByteBuffer buffer, boolean last, Callback callback)
        {
            MessageSegment messageSegment = new MessageSegment();
            messageSegment.buffer = BufferUtil.copy(buffer);
            messageSegment.last = last;
            messages.add(messageSegment);
            callback.succeed();
        }
    }

    @WebSocket
    public static class InvalidDoubleBinaryListener
    {
        @OnWebSocketMessage
        public void onMessage(ByteBuffer bytes, boolean last, Callback callback)
        {
        }

        @OnWebSocketMessage
        public void onMessage(ByteBuffer bytes, Callback callback)
        {
        }
    }

    @WebSocket
    public static class InvalidDoubleTextListener
    {
        @OnWebSocketMessage
        public void onMessage(String content, boolean last)
        {
        }

        @OnWebSocketMessage
        public void onMessage(String content)
        {
        }
    }

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
            container.setAutoFragment(false);
            container.addMapping("/", (rq, rs, cb) -> new PartialEchoSocket());
        });
        server.setHandler(context);
        server.start();
        serverUri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");

        client = new WebSocketClient();
        client.setAutoFragment(false);
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testAnnotatedPartialString() throws Exception
    {
        PartialStringListener endpoint = new PartialStringListener();
        try (Session session = client.connect(endpoint, serverUri).get(5, TimeUnit.SECONDS))
        {
            Callback.Completable.with(c -> session.sendPartialText("hell", false, c))
                .compose(c -> session.sendPartialText("o w", false, c))
                .compose(c -> session.sendPartialText("orld", true, c))
                .get();
        }

        PartialStringListener.MessageSegment segment;

        segment = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(segment.message, is("hell"));
        assertThat(segment.last, is(false));

        segment = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(segment.message, is("o w"));
        assertThat(segment.last, is(false));

        segment = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(segment.message, is("orld"));
        assertThat(segment.last, is(true));
    }

    @Test
    public void testAnnotatedPartialByteBuffer() throws Exception
    {
        PartialByteBufferListener endpoint = new PartialByteBufferListener();
        try (Session session = client.connect(endpoint, serverUri).get(5, TimeUnit.SECONDS))
        {
            Callback.Completable.with(c -> session.sendPartialBinary(BufferUtil.toBuffer("hell"), false, c))
                .compose(c -> session.sendPartialBinary(BufferUtil.toBuffer("o w"), false, c))
                .compose(c -> session.sendPartialBinary(BufferUtil.toBuffer("orld"), true, c))
                .get();
        }

        PartialByteBufferListener.MessageSegment segment;

        segment = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(segment.buffer, is(BufferUtil.toBuffer("hell")));
        assertThat(segment.last, is(false));

        segment = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(segment.buffer, is(BufferUtil.toBuffer("o w")));
        assertThat(segment.last, is(false));

        segment = Objects.requireNonNull(endpoint.messages.poll(5, TimeUnit.SECONDS));
        assertThat(segment.buffer, is(BufferUtil.toBuffer("orld")));
        assertThat(segment.last, is(true));
    }

    @Test
    public void testDoubleOnMessageAnnotation()
    {
        InvalidDoubleBinaryListener doubleBinaryListener = new InvalidDoubleBinaryListener();
        assertThrows(InvalidWebSocketException.class, () -> client.connect(doubleBinaryListener, serverUri));

        InvalidDoubleTextListener doubleTextListener = new InvalidDoubleTextListener();
        assertThrows(InvalidWebSocketException.class, () -> client.connect(doubleTextListener, serverUri));
    }
}
