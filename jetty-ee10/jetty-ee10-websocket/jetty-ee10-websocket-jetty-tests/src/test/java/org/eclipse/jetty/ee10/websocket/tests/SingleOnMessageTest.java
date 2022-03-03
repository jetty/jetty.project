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

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.api.StatusCode;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.ee10.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.ee10.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.ee10.websocket.api.util.WSURI;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SingleOnMessageTest
{
    private final Server server = new Server();
    private final WebSocketClient client = new WebSocketClient();
    private final EventSocket serverSocket = new EventSocket();
    private URI serverUri;

    @BeforeEach
    public void start() throws Exception
    {
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(contextHandler,
            (context, container) -> container.addMapping("/", (req, resp) -> serverSocket));

        server.setHandler(contextHandler);
        server.start();
        serverUri = WSURI.toWebsocket(server.getURI());

        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testTextHandler() throws Exception
    {
        TextOnlyHandler handler = new TextOnlyHandler();
        client.connect(handler, serverUri);
        assertTrue(handler.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        // The server sends a sequence of Binary and Text messages
        RemoteEndpoint remote = serverSocket.session.getRemote();
        remote.sendBytes(BufferUtil.toBuffer("this should get rejected"));
        remote.sendString("WebSocket_Data0");
        remote.sendString("WebSocket_Data1");
        serverSocket.session.close(StatusCode.NORMAL, "test complete");

        // The client receives the messages and has discarded the binary message.
        assertThat(handler.messages.poll(5, TimeUnit.SECONDS), is("WebSocket_Data0"));
        assertThat(handler.messages.poll(5, TimeUnit.SECONDS), is("WebSocket_Data1"));
        assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(handler.closeCode, is(StatusCode.NORMAL));
        assertThat(handler.closeReason, is("test complete"));
    }

    @Test
    public void testBinaryHandler() throws Exception
    {
        BinaryOnlyHandler handler = new BinaryOnlyHandler();
        client.connect(handler, serverUri);
        assertTrue(handler.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.openLatch.await(5, TimeUnit.SECONDS));

        // The server sends a sequence of Binary and Text messages
        RemoteEndpoint remote = serverSocket.session.getRemote();
        remote.sendString("this should get rejected");
        remote.sendBytes(BufferUtil.toBuffer("WebSocket_Data0"));
        remote.sendBytes(BufferUtil.toBuffer("WebSocket_Data1"));
        serverSocket.session.close(StatusCode.NORMAL, "test complete");

        // The client receives the messages and has discarded the binary message.
        assertThat(handler.messages.poll(5, TimeUnit.SECONDS), is(BufferUtil.toBuffer("WebSocket_Data0")));
        assertThat(handler.messages.poll(5, TimeUnit.SECONDS), is(BufferUtil.toBuffer("WebSocket_Data1")));
        assertTrue(handler.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(handler.closeCode, is(StatusCode.NORMAL));
        assertThat(handler.closeReason, is("test complete"));
    }

    @WebSocket
    public static class TextOnlyHandler extends AbstractHandler
    {
        final BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            messages.add(message);
        }
    }

    @WebSocket
    public static class BinaryOnlyHandler extends AbstractHandler
    {
        final BlockingArrayQueue<ByteBuffer> messages = new BlockingArrayQueue<>();

        @OnWebSocketMessage
        public void onMessage(byte[] array, int offset, int length)
        {
            messages.add(BufferUtil.toBuffer(array, offset, length));
        }
    }

    @WebSocket
    public static class AbstractHandler
    {
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        Session session;
        int closeCode;
        String closeReason;

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            this.closeCode = statusCode;
            this.closeReason = reason;
            this.closeLatch.countDown();
        }

        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            this.session = session;
            this.openLatch.countDown();
        }
    }
}
