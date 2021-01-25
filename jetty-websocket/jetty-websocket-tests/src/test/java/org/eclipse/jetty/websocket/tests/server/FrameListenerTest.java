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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
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

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder closeEndpoint = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.getPolicy().setIdleTimeout(SECONDS.toMillis(2));
                serverEndpoint = new FrameEndpoint();
                factory.setCreator((req, resp) -> serverEndpoint);
            }
        });
        context.addServlet(closeEndpoint, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

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
