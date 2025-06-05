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
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WriteTimeoutTest
{
    public static class ServerSocket implements FrameHandler
    {
        private final CountDownLatch _messageLatch = new CountDownLatch(1);

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
                assertTrue(_messageLatch.await(10, TimeUnit.SECONDS));
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

        public void unblock()
        {
            _messageLatch.countDown();
        }
    }

    private Server server;
    private WebSocketCoreClient client;
    private ServerConnector connector;

    public void start(FrameHandler serverEndpoint) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketComponents components = WebSocketServerComponents.ensureWebSocketComponents(server);
        WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler(components);
        wsHandler.addMapping("/", (req, resp, cb) -> serverEndpoint);
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
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testFrameTimeoutFromSlowReads() throws Exception
    {
        ServerSocket serverEndpoint = new ServerSocket();
        start(serverEndpoint);
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
                    session.sendFrame(new OutgoingEntry.Builder(frame, callback).frameTimeout(1000).build());
                    callback.block();
                }
            }
        });

        assertThat(exception, instanceOf(WebSocketWriteTimeoutException.class));
        assertThat(exception.getMessage(), containsString("FrameFlusher Write Timeout"));

        // Unblock the thread in onMessage() on the server endpoint.
        serverEndpoint.unblock();

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.error, instanceOf(WebSocketWriteTimeoutException.class));
    }

    @Test
    public void testMessageTimeout() throws Exception
    {
        ServerSocket serverEndpoint = new ServerSocket();
        serverEndpoint.unblock();
        start(serverEndpoint);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        TestMessageHandler clientEndpoint = new TestMessageHandler();
        client.connect(clientEndpoint, uri).get();
        CoreSession session = clientEndpoint.getCoreSession();

        // Send the first frame of the message with a 1-second timeout.
        long messageWriteTimeout = 1000;
        try (Blocker.Callback callback = Blocker.callback())
        {
            Frame frame = new Frame(OpCode.TEXT, false, "hello");
            session.sendFrame(new OutgoingEntry.Builder(frame, callback).messageTimeout(messageWriteTimeout).build());
            callback.block();
        }

        // The next frame of the message should fail because we waited half a second over the write timeout.
        Thread.sleep(messageWriteTimeout + 500);
        Exception exception = assertThrows(Exception.class, () ->
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                // The message timeout is not relevant here because it is not the first frame of the message.
                Frame frame = new Frame(OpCode.CONTINUATION, true, " world");
                session.sendFrame(new OutgoingEntry.Builder(frame, callback).build());
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
