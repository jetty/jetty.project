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

import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemandWithBlockingStreamsTest
{
    private final Server server = new Server();
    private final ServerConnector connector = new ServerConnector(server, 1, 1);
    private final WebSocketClient client = new WebSocketClient();

    private void start(Consumer<WebSocketUpgradeHandler> configurer) throws Exception
    {
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        configurer.accept(wsHandler);

        server.setHandler(context);
        server.start();

        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testBinaryStreamExplicitDemandThrows() throws Exception
    {
        StreamEndPoint serverEndPoint = new StreamEndPoint();
        start(wsHandler -> wsHandler.configure(container ->
            container.addMapping("/*", (rq, rs, cb) -> serverEndPoint)));

        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientEndPoint = new EventSocket();
        client.connect(clientEndPoint, uri).get(5, TimeUnit.SECONDS);

        clientEndPoint.session.sendBinary(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)), Callback.NOOP);

        // The server-side tried to demand(), should get an error.
        assertTrue(serverEndPoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.SERVER_ERROR, clientEndPoint.closeCode);
    }

    @Test
    public void testTextStreamExplicitDemandThrows() throws Exception
    {
        StreamEndPoint serverEndPoint = new StreamEndPoint();
        start(wsHandler -> wsHandler.configure(container ->
            container.addMapping("/*", (rq, rs, cb) -> serverEndPoint)));

        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientEndPoint = new EventSocket();
        client.connect(clientEndPoint, uri).get(5, TimeUnit.SECONDS);

        clientEndPoint.session.sendText("hello", Callback.NOOP);

        // The server-side tried to demand(), should get an error.
        assertTrue(serverEndPoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndPoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(StatusCode.SERVER_ERROR, clientEndPoint.closeCode);
    }

    @WebSocket
    public static class StreamEndPoint
    {
        private final CountDownLatch errorLatch = new CountDownLatch(1);
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private Session session;

        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }

        @OnWebSocketMessage
        public void onBinary(InputStream stream)
        {
            // Throws because this endpoint is auto-demanding.
            session.demand();
        }

        @OnWebSocketMessage
        public void onText(Reader reader)
        {
            // Throws because this endpoint is auto-demanding.
            session.demand();
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            errorLatch.countDown();
        }

        @OnWebSocketClose
        public void onClose(int status, String reason)
        {
            closeLatch.countDown();
        }
    }
}
