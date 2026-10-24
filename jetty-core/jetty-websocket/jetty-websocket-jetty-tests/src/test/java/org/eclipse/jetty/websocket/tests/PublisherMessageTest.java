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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PublisherMessageTest
{
    private Server _server;
    private WebSocketClient _client;
    private ServerConnector _connector;

    public void start(Supplier<Object> serverEndpointSupplier) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(_server, container ->
            container.addMapping("/", (rq, rs, cb) ->
                serverEndpointSupplier.get()));

        _server.setHandler(wsHandler);
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    public interface BinaryOnMessage
    {
        void accept(Session session, Flow.Publisher<ByteBuffer> publisher) throws Exception;
    }

    @WebSocket
    public record TestSocket(BinaryOnMessage onMessage)
    {
        @OnWebSocketMessage
        public void onMessage(Session session, Flow.Publisher<ByteBuffer> publisher) throws Exception
        {
            onMessage.accept(session, publisher);
        }
    }

    /**
     * Requests the frames of a message one at a time, and sends the frame
     * count and the whole message back as text when the message is complete.
     */
    private static class EchoSubscriber implements Flow.Subscriber<ByteBuffer>
    {
        private final StringBuilder message = new StringBuilder();
        private final Session session;
        private Flow.Subscription subscription;
        private int frames;

        private EchoSubscriber(Session session)
        {
            this.session = session;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(ByteBuffer payload)
        {
            frames++;
            message.append(BufferUtil.toString(payload, StandardCharsets.UTF_8));
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure)
        {
            session.sendText("error:" + failure, Callback.NOOP);
        }

        @Override
        public void onComplete()
        {
            session.sendText(frames + ":" + message, Callback.NOOP);
        }
    }

    private Session connect(EventSocket clientEndpoint) throws Exception
    {
        URI uri = new URI("ws://localhost:" + _connector.getLocalPort());
        return _client.connect(clientEndpoint, uri).get(5, TimeUnit.SECONDS);
    }

    private static void sendFragmented(Session session, String... fragments)
    {
        for (String fragment : fragments)
        {
            session.sendPartialBinary(BufferUtil.toBuffer(fragment), false, Callback.NOOP);
        }
        session.sendPartialBinary(BufferUtil.EMPTY_BUFFER, true, Callback.NOOP);
    }

    @Test
    public void testMessageIsPublishedOneFrameAtATime() throws Exception
    {
        start(() -> new TestSocket((session, publisher) -> publisher.subscribe(new EchoSubscriber(session))));

        EventSocket clientEndpoint = new EventSocket();
        Session session = connect(clientEndpoint);

        for (int i = 0; i < 3; i++)
        {
            sendFragmented(session, "hello", " world");
            assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("2:hello world"));
        }

        session.sendBinary(BufferUtil.toBuffer("whole"), Callback.NOOP);
        assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("1:whole"));

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testSubscriptionAfterEndpointMethodReturns() throws Exception
    {
        // The endpoint method hands the publisher over to another thread and returns.
        start(() -> new TestSocket((session, publisher) ->
            CompletableFuture.runAsync(() -> publisher.subscribe(new EchoSubscriber(session)))));

        EventSocket clientEndpoint = new EventSocket();
        Session session = connect(clientEndpoint);

        for (int i = 0; i < 3; i++)
        {
            sendFragmented(session, "hello", " world");
            assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("2:hello world"));
        }

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testCancelDiscardsTheRestOfTheMessage() throws Exception
    {
        start(() -> new TestSocket((session, publisher) -> publisher.subscribe(new Flow.Subscriber<>()
        {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ByteBuffer payload)
            {
                subscription.cancel();
                session.sendText("cancelled:" + BufferUtil.toString(payload, StandardCharsets.UTF_8), Callback.NOOP);
            }

            @Override
            public void onError(Throwable failure)
            {
                session.sendText("error:" + failure, Callback.NOOP);
            }

            @Override
            public void onComplete()
            {
                session.sendText("completed", Callback.NOOP);
            }
        })));

        EventSocket clientEndpoint = new EventSocket();
        Session session = connect(clientEndpoint);

        // The rest of each message is discarded, and the next message is published.
        for (int i = 0; i < 3; i++)
        {
            sendFragmented(session, "hello", " world");
            assertThat(clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS), equalTo("cancelled:hello"));
        }

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testSubscriberFailureClosesTheSession() throws Exception
    {
        start(() -> new TestSocket((session, publisher) -> publisher.subscribe(new Flow.Subscriber<>()
        {
            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer payload)
            {
                throw new IllegalStateException("subscriber failure");
            }

            @Override
            public void onError(Throwable failure)
            {
            }

            @Override
            public void onComplete()
            {
            }
        })));

        EventSocket clientEndpoint = new EventSocket();
        Session session = connect(clientEndpoint);

        session.sendBinary(BufferUtil.toBuffer("hello"), Callback.NOOP);

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.SERVER_ERROR));
    }

    @Test
    public void testEndpointMethodFailureClosesTheSession() throws Exception
    {
        start(() -> new TestSocket((session, publisher) ->
        {
            throw new IllegalStateException("endpoint failure");
        }));

        EventSocket clientEndpoint = new EventSocket();
        Session session = connect(clientEndpoint);

        session.sendBinary(BufferUtil.toBuffer("hello"), Callback.NOOP);

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.SERVER_ERROR));
    }
}
