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

package org.eclipse.jetty.ee9.websocket.tests.server;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Frame;
import org.eclipse.jetty.ee9.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.common.WebSocketSession;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee9.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.OpCode;
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

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder closeEndpoint = new ServletHolder(new JettyWebSocketServlet()
        {
            @Override
            public void configure(JettyWebSocketServletFactory factory)
            {
                factory.setIdleTimeout(Duration.ofSeconds(2));
                serverEndpoint = new FrameEndpoint();
                factory.setCreator((req, resp) -> serverEndpoint);
            }
        });
        context.addServlet(closeEndpoint, "/ws");
        JettyWebSocketServletContainerInitializer.configure(context, null);

        server.setHandler(new HandlerList(context, new DefaultHandler()));

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

            RemoteEndpoint clientRemote = session.getRemote();
            clientRemote.sendPartialString("hello", false);
            clientRemote.sendPartialString(" ", false);
            clientRemote.sendPartialString("world", true);

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

    public static class FrameEndpoint implements WebSocketFrameListener
    {
        public Session session;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public LinkedBlockingQueue<String> frameEvents = new LinkedBlockingQueue<>();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }

        @Override
        public void onWebSocketFrame(Frame frame)
        {
            frameEvents.offer(String.format("FRAME[%s,fin=%b,payload=%s,len=%d]",
                OpCode.name(frame.getOpCode()),
                frame.isFin(),
                BufferUtil.toUTF8String(frame.getPayload()),
                frame.getPayloadLength()));
        }
    }
}
