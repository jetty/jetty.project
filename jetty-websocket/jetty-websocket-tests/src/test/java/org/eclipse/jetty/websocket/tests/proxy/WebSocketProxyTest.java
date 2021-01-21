//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketProxyTest
{
    private Server server;
    private EventSocket serverSocket;
    private WebSocketProxy webSocketProxy;
    private WebSocketClient client;
    private URI proxyUri;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        WebSocketUpgradeFilter.configure(contextHandler);
        serverSocket = new EchoSocket();
        NativeWebSocketServletContainerInitializer.configure(contextHandler, ((context, container) ->
        {
            container.addMapping("/proxy", (req, resp) -> webSocketProxy.getWebSocketConnectionListener());
            container.addMapping("/echo", (req, resp) ->
            {
                if (req.hasSubProtocol("fail"))
                    throw new WebSocketException("failing during upgrade");
                return serverSocket;
            });
        }));

        server.setHandler(contextHandler);
        server.start();

        int port = connector.getLocalPort();

        client = new WebSocketClient();
        client.start();
        proxyUri = URI.create("ws://localhost:" + port + "/proxy");
        URI echoUri = URI.create("ws://localhost:" + port + "/echo");
        webSocketProxy = new WebSocketProxy(client, echoUri);
    }

    @AfterEach
    public void after() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testEcho() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, proxyUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));

        // Test an echo spread across multiple frames.
        clientSocket.session.getRemote().sendPartialString("hell", false);
        clientSocket.session.getRemote().sendPartialString("o w", false);
        clientSocket.session.getRemote().sendPartialString("orld", false);
        clientSocket.session.getRemote().sendPartialString("!", true);
        String response = clientSocket.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(response, is("hello world!"));

        // Test we closed successfully on the client side.
        clientSocket.session.close(StatusCode.NORMAL, "test initiated close");
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(clientSocket.closeReason, is("test initiated close"));

        // Test we closed successfully on the server side.
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(serverSocket.closeReason, is("test initiated close"));

        // No errors occurred.
        assertNull(clientSocket.error);
        assertNull(serverSocket.error);

        // WebSocketProxy has been completely closed.
        assertTrue(webSocketProxy.awaitClose(5000));
    }

    @Test
    public void testFailServerUpgrade() throws Exception
    {
        EventSocket clientSocket = new EventSocket();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("fail");

        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            client.connect(clientSocket, proxyUri, upgradeRequest);
            assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        }

        // WebSocketProxy has been completely closed.
        assertTrue(webSocketProxy.awaitClose(5000));
    }

    @Test
    public void testClientError() throws Exception
    {
        EventSocket clientSocket = new OnOpenThrowingSocket();
        client.connect(clientSocket, proxyUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));

        // Verify expected client close.
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, is(StatusCode.NO_CLOSE));
        assertThat(clientSocket.closeReason, is("simulated onOpen error"));
        assertNotNull(clientSocket.error);

        // Verify expected server close.
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.NO_CLOSE));
        assertThat(serverSocket.closeReason, is("Disconnected"));
        assertNull(serverSocket.error);

        // WebSocketProxy has been completely closed.
        assertTrue(webSocketProxy.awaitClose(5000));
    }

    @Test
    public void testServerError() throws Exception
    {
        serverSocket = new OnOpenThrowingSocket();

        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, proxyUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));

        // Verify expected client close.
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, is(StatusCode.SERVER_ERROR));
        assertThat(clientSocket.closeReason, is("simulated onOpen error"));
        assertNull(clientSocket.error);

        // Verify expected server close.
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.SERVER_ERROR));
        assertThat(serverSocket.closeReason, is("simulated onOpen error"));
        assertNotNull(serverSocket.error);

        // WebSocketProxy has been completely closed.
        assertTrue(webSocketProxy.awaitClose(5000));
    }

    @Test
    public void testServerThrowsOnMessage() throws Exception
    {
        serverSocket = new OnTextThrowingSocket();

        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, proxyUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        clientSocket.session.getRemote().sendString("hello world!");

        // Verify expected client close.
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, is(StatusCode.SERVER_ERROR));
        assertThat(clientSocket.closeReason, is("simulated onMessage error"));
        assertNull(clientSocket.error);

        // Verify expected server close.
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.SERVER_ERROR));
        assertThat(serverSocket.closeReason, is("simulated onMessage error"));
        assertNotNull(serverSocket.error);

        assertNull(clientSocket.textMessages.poll(1, TimeUnit.SECONDS));
        assertTrue(webSocketProxy.awaitClose(5000));
    }

    @Test
    public void timeoutTest() throws Exception
    {
        long clientSessionIdleTimeout = 2000;

        EventSocket clientSocket = new EventSocket();
        client.connect(clientSocket, proxyUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        // Configure infinite idleTimeout on the server session and short timeout on the client session.
        clientSocket.session.setIdleTimeout(clientSessionIdleTimeout);
        serverSocket.session.setIdleTimeout(-1);

        // Send and receive an echo message.
        clientSocket.session.getRemote().sendString("test echo message");
        assertThat(clientSocket.textMessages.poll(clientSessionIdleTimeout, TimeUnit.SECONDS), is("test echo message"));

        // Wait more than the idleTimeout period, the clientToProxy connection should fail which should fail the proxyToServer.
        assertTrue(clientSocket.closeLatch.await(clientSessionIdleTimeout * 2, TimeUnit.MILLISECONDS));
        assertTrue(serverSocket.closeLatch.await(clientSessionIdleTimeout * 2, TimeUnit.MILLISECONDS));

        // Check errors and close status.
        assertThat(clientSocket.error.getMessage(), containsString("Idle timeout expired"));
        assertThat(clientSocket.closeCode, is(StatusCode.SHUTDOWN));
        assertThat(clientSocket.closeReason, containsString("Idle timeout expired"));
        assertNull(serverSocket.error);
        assertThat(serverSocket.closeCode, is(StatusCode.SHUTDOWN));
        assertThat(serverSocket.closeReason, containsString("Idle timeout expired"));
    }

    @Test
    public void testPingPong() throws Exception
    {
        PingPongSocket serverEndpoint = new PingPongSocket();
        serverSocket = serverEndpoint;

        PingPongSocket clientSocket = new PingPongSocket();
        client.connect(clientSocket, proxyUri);
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        // Test unsolicited pong from client.
        clientSocket.session.getRemote().sendPong(BufferUtil.toBuffer("unsolicited pong from client"));
        assertThat(serverEndpoint.pingMessages.size(), is(0));
        assertThat(serverEndpoint.pongMessages.poll(5, TimeUnit.SECONDS), is(BufferUtil.toBuffer("unsolicited pong from client")));

        // Test unsolicited pong from server.
        serverEndpoint.session.getRemote().sendPong(BufferUtil.toBuffer("unsolicited pong from server"));
        assertThat(clientSocket.pingMessages.size(), is(0));
        assertThat(clientSocket.pongMessages.poll(5, TimeUnit.SECONDS), is(BufferUtil.toBuffer("unsolicited pong from server")));

        // Test pings from client.
        for (int i = 0; i < 15; i++)
            clientSocket.session.getRemote().sendPing(intToStringByteBuffer(i));
        for (int i = 0; i < 15; i++)
        {
            assertThat(serverEndpoint.pingMessages.poll(5, TimeUnit.SECONDS), is(intToStringByteBuffer(i)));
            assertThat(clientSocket.pongMessages.poll(5, TimeUnit.SECONDS), is(intToStringByteBuffer(i)));
        }

        // Test pings from server.
        for (int i = 0; i < 23; i++)
            serverEndpoint.session.getRemote().sendPing(intToStringByteBuffer(i));
        for (int i = 0; i < 23; i++)
        {
            assertThat(clientSocket.pingMessages.poll(5, TimeUnit.SECONDS), is(intToStringByteBuffer(i)));
            assertThat(serverEndpoint.pongMessages.poll(5, TimeUnit.SECONDS), is(intToStringByteBuffer(i)));
        }

        clientSocket.session.close(StatusCode.NORMAL, "closing from test");

        // Verify expected client close.
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(clientSocket.closeReason, is("closing from test"));
        assertNull(clientSocket.error);

        // Verify expected server close.
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(serverSocket.closeReason, is("closing from test"));
        assertNull(serverSocket.error);

        // WebSocketProxy has been completely closed.
        assertTrue(webSocketProxy.awaitClose(5000));

        // Check we had no unexpected pings or pongs sent.
        assertThat(clientSocket.pingMessages.size(), is(0));
        assertThat(serverEndpoint.pingMessages.size(), is(0));
    }

    private ByteBuffer intToStringByteBuffer(int i)
    {
        return BufferUtil.toBuffer(Integer.toString(i));
    }

    @WebSocket
    public static class PingPongSocket extends EventSocket
    {
        public BlockingQueue<ByteBuffer> pingMessages = new BlockingArrayQueue<>();
        public BlockingQueue<ByteBuffer> pongMessages = new BlockingArrayQueue<>();

        @OnWebSocketFrame
        public void onWebSocketFrame(Frame frame)
        {
            switch (frame.getOpCode())
            {
                case OpCode.PING:
                    pingMessages.add(BufferUtil.copy(frame.getPayload()));
                    break;
                case OpCode.PONG:
                    pongMessages.add(BufferUtil.copy(frame.getPayload()));
                    break;
                default:
                    break;
            }
        }
    }

    @WebSocket
    public static class OnOpenThrowingSocket extends EventSocket
    {
        @Override
        public void onOpen(Session session)
        {
            super.onOpen(session);
            throw new IllegalStateException("simulated onOpen error");
        }
    }

    @WebSocket
    public static class OnTextThrowingSocket extends EventSocket
    {
        @Override
        public void onMessage(String message) throws IOException
        {
            super.onMessage(message);
            throw new IllegalStateException("simulated onMessage error");
        }
    }
}
