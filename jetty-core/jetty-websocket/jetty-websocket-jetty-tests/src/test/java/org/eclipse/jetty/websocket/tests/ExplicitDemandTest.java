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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExplicitDemandTest
{
    @WebSocket(autoDemand = false)
    public static class SuspendSocket extends EventSocket
    {
        @Override
        public void onOpen(Session session)
        {
            super.onOpen(session);
            session.demand();
        }

        @Override
        public void onMessage(String message) throws IOException
        {
            super.onMessage(message);
            if (!"suspend".equals(message))
                session.demand();
        }
    }

    @WebSocket(autoDemand = false)
    public static class ListenerSocket implements Session.Listener
    {
        final List<Frame> frames = new CopyOnWriteArrayList<>();
        Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            session.demand();
        }

        @Override
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            frames.add(Frame.copy(frame));

            // Because no pingListener is registered, the frameListener is responsible for handling pings.
            if (frame.getOpCode() == OpCode.PING)
            {
                session.sendPong(frame.getPayload(), Callback.from(callback, session::demand));
                return;
            }

            callback.succeed();
            session.demand();
        }
    }

    @WebSocket(autoDemand = false)
    public static class OnOpenSocket implements Session.Listener
    {
        CountDownLatch onOpen = new CountDownLatch(1);
        BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
        Session session;

        @Override
        public void onWebSocketOpen(Session session)
        {
            try
            {
                this.session = session;
                session.demand();
                onOpen.await();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            if (frame.getOpCode() == OpCode.TEXT)
                textMessages.add(BufferUtil.toString(frame.getPayload()));
            callback.succeed();
            session.demand();
        }
    }

    @WebSocket(autoDemand = false)
    public static class PingSocket extends ListenerSocket
    {
        @Override
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            if (frame.getType() == Frame.Type.TEXT)
                session.sendPing(ByteBuffer.wrap("server-ping".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
            super.onWebSocketFrame(frame, callback);
        }
    }

    private final Server server = new Server();
    private final WebSocketClient client = new WebSocketClient();
    private final SuspendSocket serverSocket = new SuspendSocket();
    private final ListenerSocket listenerSocket = new ListenerSocket();
    private final OnOpenSocket onOpenSocket = new OnOpenSocket();
    private final PingSocket pingSocket = new PingSocket();
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, container ->
        {
            container.addMapping("/suspend", (rq, rs, cb) -> serverSocket);
            container.addMapping("/listenerSocket", (rq, rs, cb) -> listenerSocket);
            container.addMapping("/ping", (rq, rs, cb) -> pingSocket);
            container.addMapping("/onOpen", (rq, rs, cb) -> onOpenSocket);
        });

        server.setHandler(wsHandler);
        server.start();

        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testNoDemandWhenProcessingFrame() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/suspend");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);

        clientSocket.session.sendText("suspend", Callback.NOOP);
        clientSocket.session.sendText("suspend", Callback.NOOP);
        clientSocket.session.sendText("hello world", Callback.NOOP);

        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        serverSocket.session.demand();
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        serverSocket.session.demand();
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check no errors occurred
        assertNull(clientSocket.error);
        assertNull(serverSocket.error);
    }

    @Test
    public void testNoAutoDemand() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/listenerSocket");
        ListenerSocket listenerSocket = new ListenerSocket();
        Future<Session> connect = client.connect(listenerSocket, uri);
        Session session = connect.get(5, TimeUnit.SECONDS);

        session.sendPing(ByteBuffer.wrap("ping-0".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);
        session.sendText("test-text", Callback.NOOP);
        session.sendPing(ByteBuffer.wrap("ping-1".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);

        await().atMost(5, TimeUnit.SECONDS).until(listenerSocket.frames::size, is(2));
        Frame frame0 = listenerSocket.frames.get(0);
        assertThat(frame0.getType(), is(Frame.Type.PONG));
        assertThat(StandardCharsets.UTF_8.decode(frame0.getPayload()).toString(), is("ping-0"));
        Frame frame1 = listenerSocket.frames.get(1);
        assertThat(frame1.getType(), is(Frame.Type.PONG));
        assertThat(StandardCharsets.UTF_8.decode(frame1.getPayload()).toString(), is("ping-1"));

        session.close();
        await().atMost(5, TimeUnit.SECONDS).until(listenerSocket.frames::size, is(3));
        assertThat(listenerSocket.frames.get(2).getType(), is(Frame.Type.CLOSE));
    }

    @Test
    public void testServerPing() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/ping");
        PingSocket pingSocket = new PingSocket();
        Future<Session> connect = client.connect(pingSocket, uri);
        Session session = connect.get(5, TimeUnit.SECONDS);

        session.sendText("send-me-a-ping", Callback.NOOP);

        await().atMost(5, TimeUnit.SECONDS).until(pingSocket.frames::size, is(1));
        Frame frame = pingSocket.frames.get(0);
        assertThat(frame.getType(), is(Frame.Type.PING));
        assertThat(StandardCharsets.UTF_8.decode(frame.getPayload()).toString(), is("server-ping"));

        session.sendText("send-me-another-ping", Callback.NOOP);

        await().atMost(5, TimeUnit.SECONDS).until(pingSocket.frames::size, is(2));
        frame = pingSocket.frames.get(1);
        assertThat(frame.getType(), is(Frame.Type.PING));
        assertThat(StandardCharsets.UTF_8.decode(frame.getPayload()).toString(), is("server-ping"));

        session.close();
        await().atMost(5, TimeUnit.SECONDS).until(pingSocket.frames::size, is(3));
        frame = pingSocket.frames.get(2);
        assertThat(frame.getType(), is(Frame.Type.CLOSE));
    }

    @Test
    public void testDemandInOnOpen() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/onOpen");
        EventSocket clientSocket = new EventSocket();

        Future<Session> connect = client.connect(clientSocket, uri);
        Session session = connect.get(5, TimeUnit.SECONDS);
        session.sendText("test-text", Callback.NOOP);

        // We cannot receive messages while in onOpen, even if we have demanded.
        assertNull(onOpenSocket.textMessages.poll(1, TimeUnit.SECONDS));

        // Once we leave onOpen we receive the message.
        onOpenSocket.onOpen.countDown();
        String received = onOpenSocket.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received, equalTo("test-text"));

        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, equalTo(CloseStatus.NORMAL));
    }
}
