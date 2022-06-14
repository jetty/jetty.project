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

package org.eclipse.jetty.ee10.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.common.WebSocketSession;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.compression.CompressionPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PermessageDeflateBufferTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private JettyWebSocketServerContainer serverContainer;
    private final FailEndPointOutgoing outgoingFailEndPoint = new FailEndPointOutgoing();
    private final FailEndPointIncoming incomingFailEndPoint = new FailEndPointIncoming();
    private final ServerSocket serverSocket = new ServerSocket();

    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    private static final List<String> DICT = Arrays.asList(
        "\uD83C\uDF09",
        "\uD83C\uDF0A",
        "\uD83C\uDF0B",
        "\uD83C\uDF0C",
        "\uD83C\uDF0D",
        "\uD83C\uDF0F",
        "\uD83C\uDFC0",
        "\uD83C\uDFC1",
        "\uD83C\uDFC2",
        "\uD83C\uDFC3",
        "\uD83C\uDFC4",
        "\uD83C\uDFC5"
    );

    private static String randomText()
    {
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15000; i++)
        {
            sb.append(DICT.get(rnd.nextInt(DICT.size())));
        }
        return sb.toString();
    }

    private static ByteBuffer randomBytes(int size)
    {
        var bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return BufferUtil.toBuffer(bytes);
    }

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.setMaxTextMessageSize(65535);
            container.setInputBufferSize(16384);
            container.addMapping("/", (req, resp) -> serverSocket);
            container.addMapping("/outgoingFail", (req, resp) -> outgoingFailEndPoint);
            container.addMapping("/incomingFail", (req, resp) -> incomingFailEndPoint);
        });

        server.start();
        serverContainer = JettyWebSocketServerContainer.getContainer(contextHandler.getServletContext());
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        client.stop();
        server.stop();
    }

    @WebSocket
    public static class ServerSocket extends EchoSocket
    {
        @Override
        public void onError(Throwable cause)
        {
            cause.printStackTrace();
            super.onError(cause);
        }
    }

    @Test
    public void testPermessageDeflateAggregation() throws Exception
    {
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        String s = randomText();
        session.getRemote().sendString(s);
        assertThat(socket.textMessages.poll(5, TimeUnit.SECONDS), is(s));

        session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testPermessageDeflateFragmentedBinaryMessage() throws Exception
    {
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        ByteBuffer message = randomBytes(1024);
        session.setMaxFrameSize(64);
        session.getRemote().sendBytes(message);
        assertThat(socket.binaryMessages.poll(5, TimeUnit.SECONDS), equalTo(message));

        session.close();
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(socket.closeCode, equalTo(StatusCode.NORMAL));
    }

    @Test
    public void testClientPartialMessageThenServerIdleTimeout() throws Exception
    {
        Duration idleTimeout = Duration.ofMillis(1000);
        serverContainer.setIdleTimeout(idleTimeout);

        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        EventSocket socket = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/incomingFail");
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        session.getRemote().sendPartialString("partial", false);

        // Wait for the idle timeout to elapse.
        assertTrue(incomingFailEndPoint.closeLatch.await(5, TimeUnit.SECONDS));

        server.getContainedBeans(InflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased inflater pool entries: " + pool.dump()));
        server.getContainedBeans(DeflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased deflater pool entries: " + pool.dump()));
    }

    @Test
    public void testClientPartialMessageThenClientClose() throws Exception
    {
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        PartialTextSocket socket = new PartialTextSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/incomingFail");
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        session.getRemote().sendPartialString("partial", false);
        // Wait for the server to process the partial message.
        assertThat(socket.partialMessages.poll(5, TimeUnit.SECONDS), equalTo("partial" + "last=true"));

        // Abruptly close the connection from the client.
        ((WebSocketCoreSession)((WebSocketSession)session).getCoreSession()).getConnection().getEndPoint().close();

        // Wait for the server to process the close.
        assertTrue(incomingFailEndPoint.closeLatch.await(5, TimeUnit.SECONDS));

        server.getContainedBeans(InflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased inflater pool entries: " + pool.dump()));
        server.getContainedBeans(DeflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased deflater pool entries: " + pool.dump()));
    }

    @Test
    public void testServerPartialMessageThenServerIdleTimeout() throws Exception
    {
        Duration idleTimeout = Duration.ofMillis(1000);
        serverContainer.setIdleTimeout(idleTimeout);

        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        EventSocket socket = new EventSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/outgoingFail");
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        session.getRemote().sendString("hello");

        // Wait for the idle timeout to elapse.
        assertTrue(outgoingFailEndPoint.closeLatch.await(2 * idleTimeout.toMillis(), TimeUnit.SECONDS));

        server.getContainedBeans(InflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased inflater pool entries: " + pool.dump()));
        server.getContainedBeans(DeflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased deflater pool entries: " + pool.dump()));
    }

    @Test
    public void testServerPartialMessageThenClientClose() throws Exception
    {
        ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
        clientUpgradeRequest.addExtensions("permessage-deflate");

        PartialTextSocket socket = new PartialTextSocket();
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/outgoingFail");
        Session session = client.connect(socket, uri, clientUpgradeRequest).get(5, TimeUnit.SECONDS);

        session.getRemote().sendString("hello");
        // Wait for the server to process the message.
        assertThat(socket.partialMessages.poll(5, TimeUnit.SECONDS), equalTo("hello" + "last=false"));

        // Abruptly close the connection from the client.
        ((WebSocketCoreSession)((WebSocketSession)session).getCoreSession()).getConnection().getEndPoint().close();

        // Wait for the server to process the close.
        assertTrue(outgoingFailEndPoint.closeLatch.await(5, TimeUnit.SECONDS));

        server.getContainedBeans(InflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased inflater pool entries: " + pool.dump()));
        server.getContainedBeans(DeflaterPool.class).stream()
            .map(CompressionPool::getPool)
            .forEach(pool -> assertEquals(0, pool.getInUseCount(), "unreleased deflater pool entries: " + pool.dump()));
    }

    @WebSocket
    public static class PartialTextSocket
    {
        private static final Logger LOG = LoggerFactory.getLogger(EventSocket.class);

        public Session session;
        public BlockingQueue<String> partialMessages = new BlockingArrayQueue<>();
        public CountDownLatch openLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);

        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            this.session = session;
            openLatch.countDown();
        }

        @OnWebSocketMessage
        public void onMessage(String message, boolean last) throws IOException
        {
            partialMessages.offer(message + "last=" + last);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }
    }

    @WebSocket
    public static class FailEndPointOutgoing
    {
        public CountDownLatch closeLatch = new CountDownLatch(1);

        @OnWebSocketMessage
        public void onMessage(Session session, String message) throws IOException
        {
            session.getRemote().sendPartialString(message, false);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }
    }

    @WebSocket
    public static class FailEndPointIncoming
    {
        public CountDownLatch closeLatch = new CountDownLatch(1);

        @OnWebSocketMessage
        public void onMessage(Session session, String message, boolean last) throws IOException
        {
            session.getRemote().sendString(message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }
    }
}
