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

package org.eclipse.jetty.websocket.core;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WriteTimeoutTest
{
    private static final CountDownLatch MESSAGE_LATCH = new CountDownLatch(1);

    public static class ServerSocket implements FrameHandler
    {
        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            coreSession.setIdleTimeout(Duration.ZERO);
            coreSession.setMaxTextMessageSize(-1);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            try
            {
                assertTrue(MESSAGE_LATCH.await(10, TimeUnit.SECONDS));
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
        }
    }

    private Server server;
    private WebSocketCoreClient client;
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketComponents components = WebSocketServerComponents.ensureWebSocketComponents(server);
        WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler(components);
        wsHandler.addMapping("/", (req, resp, cb) -> new ServerSocket());
        wsHandler.getConfiguration().setIdleTimeout(Duration.ZERO);
        server.setHandler(wsHandler);

        client = new WebSocketCoreClient();
        client.getHttpClient().setIdleTimeout(-1);
        client.start();
        server.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testFrameTimeoutFromSlowReads() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        TestMessageHandler clientEndpoint = new TestMessageHandler();
        client.connect(clientEndpoint, uri).get();
        CoreSession session = clientEndpoint.getCoreSession();

        // Keep sending messages until one times out because the server is not reading and blocked on the countdown latch.
        Exception exception = assertThrows(Exception.class, () ->
        {
            while (session.isOutputOpen())
            {
                try (Blocker.Callback callback = Blocker.callback())
                {
                    Frame frame = new Frame(OpCode.TEXT, true, "x".repeat(1024));
                    session.sendFrame(new OutgoingEntry(frame, callback, false, 1000, -1));
                    callback.block();
                }
            }
        });

        assertThat(exception, instanceOf(WebSocketWriteTimeoutException.class));
        assertThat(exception.getMessage(), containsString("FrameFlusher Write Timeout"));

        // Unblock the thread in onMessage() on the server endpoint.
        MESSAGE_LATCH.countDown();

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.error, instanceOf(WebSocketWriteTimeoutException.class));
    }

    @Test
    public void testMessageTimeout() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        TestMessageHandler clientEndpoint = new TestMessageHandler();
        client.connect(clientEndpoint, uri).get();
        CoreSession session = clientEndpoint.getCoreSession();
        MESSAGE_LATCH.countDown();

        // Send the first frame of the message with a 1-second timeout.
        try (Blocker.Callback callback = Blocker.callback())
        {
            Frame frame = new Frame(OpCode.TEXT, false, "hello");
            session.sendFrame(new OutgoingEntry(frame, callback, false, -1, 1000));
            callback.block();
        }

        // The next frame of the message should fail because we waited over a second.
        Thread.sleep(1100);
        Exception exception = assertThrows(Exception.class, () ->
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                // The message timeout is not relevant here because it is not the first frame of the message.
                Frame frame = new Frame(OpCode.CONTINUATION, true, " world");
                session.sendFrame(new OutgoingEntry(frame, callback, false, -1, -1));
                callback.block();
            }
        });
        assertThat(exception, instanceOf(WebSocketWriteTimeoutException.class));
        assertThat(exception.getMessage(), containsString("FrameFlusher Write Timeout"));

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.error, instanceOf(WebSocketWriteTimeoutException.class));
    }
}
