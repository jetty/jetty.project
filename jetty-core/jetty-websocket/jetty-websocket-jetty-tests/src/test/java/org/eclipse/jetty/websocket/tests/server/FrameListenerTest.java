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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class FrameListenerTest
{
    private Server server;
    private FrameEndpoint serverEndpoint;
    private WebSocketClient client;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
        {
            container.setIdleTimeout(Duration.ofSeconds(2));
            container.addMapping("/ws", (rq, rs, cb) -> serverEndpoint = new FrameEndpoint());
        });

        server.setHandler(context);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    private void close(Session session)
    {
        if (session != null)
        {
            session.close();
        }
    }

    @Test
    public void testPartialText() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            session.sendPartialText("hello", false, Callback.NOOP);
            session.sendPartialText(" ", false, Callback.NOOP);
            session.sendPartialText("world", true, Callback.NOOP);

            String event = serverEndpoint.frameEvents.poll(5, SECONDS);
            assertThat("Event", event, is("FRAME[TEXT,fin=false,payload=hello,len=5]"));
            event = serverEndpoint.frameEvents.poll(5, SECONDS);
            assertThat("Event", event, is("FRAME[CONTINUATION,fin=false,payload= ,len=1]"));
            event = serverEndpoint.frameEvents.poll(5, SECONDS);
            assertThat("Event", event, is("FRAME[CONTINUATION,fin=true,payload=world,len=5]"));
        }
        finally
        {
            close(session);
        }
    }

    public static class FrameEndpoint implements Session.Listener
    {
        public Session session;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public LinkedBlockingQueue<String> frameEvents = new LinkedBlockingQueue<>();

        @Override
        public void onWebSocketOpen(Session session)
        {
            this.session = session;
            session.demand();
        }

        @Override
        public void onWebSocketFrame(Frame frame, Callback callback)
        {
            frameEvents.offer(String.format("FRAME[%s,fin=%b,payload=%s,len=%d]",
                OpCode.name(frame.getOpCode()),
                frame.isFin(),
                BufferUtil.toUTF8String(frame.getPayload()),
                frame.getPayloadLength()));
            callback.succeed();
            session.demand();
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }
    }
}
