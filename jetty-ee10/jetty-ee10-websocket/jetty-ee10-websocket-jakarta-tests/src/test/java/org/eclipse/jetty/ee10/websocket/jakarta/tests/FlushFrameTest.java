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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.core.CloseStatus.NORMAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlushFrameTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketCoreClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.addEndpoint(WebSocketServerEndpoint.class);
        });
        _server.setHandler(contextHandler);
        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @ServerEndpoint("/")
    public static class WebSocketServerEndpoint extends EventSocket
    {
        @Override
        public void onOpen(Session session, EndpointConfig endpointConfig)
        {
            super.onOpen(session, endpointConfig);

            try
            {
                session.getBasicRemote().setBatchingAllowed(true);
                session.getBasicRemote().flushBatch();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testFlushWithPermessageDeflate() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        TestFrameHandler testFrameHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, testFrameHandler);
        upgradeRequest.addExtensions("permessage-deflate");

        // Once WebSocket connection is opened the server does a flush.
        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        coreSession.close(NORMAL, null, Callback.NOOP);

        // Receive the close frame and succeed the callback.
        TestFrameHandler.FrameCallback received = testFrameHandler.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(received);
        assertThat(received.frame.getOpCode(), equalTo(OpCode.CLOSE));
        received.callback.succeeded();

        // FrameHandler is closed normally.
        assertTrue(testFrameHandler.closeLatch.await(5, TimeUnit.SECONDS));
        assertNull(testFrameHandler.error);
        assertThat(testFrameHandler.closeStatus.getCode(), equalTo(NORMAL));

        // We received no other frames.
        assertTrue(testFrameHandler.received.isEmpty());
    }

    public static class TestFrameHandler implements FrameHandler
    {
        CoreSession coreSession;
        Throwable error;
        CloseStatus closeStatus;
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        BlockingArrayQueue<FrameCallback> received = new BlockingArrayQueue<>();

        public static class FrameCallback
        {
            public FrameCallback(Frame frame, Callback callback)
            {
                this.frame = frame;
                this.callback = callback;
            }

            public Frame frame;
            public Callback callback;
        }

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            this.coreSession = coreSession;
            callback.succeeded();
            coreSession.demand();
            openLatch.countDown();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            received.offer(new FrameCallback(frame, callback));
            coreSession.demand();
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            this.error = cause;
            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            this.closeStatus = closeStatus;
            callback.succeeded();
            closeLatch.countDown();
        }
    }
}
