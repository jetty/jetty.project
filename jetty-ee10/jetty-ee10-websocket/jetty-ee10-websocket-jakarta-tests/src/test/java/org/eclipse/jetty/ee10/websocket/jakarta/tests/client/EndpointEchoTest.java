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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSEndpointTracker;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EndpointEchoTest
{
    private static LocalServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(LocalServer.TextEchoSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    public static class ClientEndpoint extends WSEndpointTracker implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            super.onOpen(session, config);
            session.addMessageHandler(this);
        }

        @Override
        public void onMessage(String message)
        {
            super.onWsText(message);
        }
    }

    @Test
    public void testEchoInstance() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        ClientEndpoint clientEndpoint = new ClientEndpoint();
        assertThat(clientEndpoint, Matchers.instanceOf(jakarta.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        Session session = container.connectToServer(clientEndpoint, null, server.getWsUri().resolve("/echo/text"));
        session.getBasicRemote().sendText("Echo");

        String resp = clientEndpoint.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("Echo"));
        session.close();
        clientEndpoint.awaitCloseEvent("Client");
    }

    @Test
    public void testEchoClassRef() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        // Issue connect using class reference (class extends Endpoint)
        Session session = container.connectToServer(ClientEndpoint.class, null, server.getWsUri().resolve("/echo/text"));
        session.getBasicRemote().sendText("Echo");

        JakartaWebSocketSession jsrSession = (JakartaWebSocketSession)session;
        Object obj = jsrSession.getEndpoint();

        assertThat("session.endpoint", obj, Matchers.instanceOf(ClientEndpoint.class));
        ClientEndpoint endpoint = (ClientEndpoint)obj;
        String resp = endpoint.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("Echo"));

        session.close();
        endpoint.awaitCloseEvent("Client");
    }

    @Test
    public void testEchoAnonymousInstance() throws Exception
    {
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
        Endpoint clientEndpoint = new Endpoint()
        {
            @Override
            public void onOpen(Session session, EndpointConfig config)
            {
                // Cannot replace this with a lambda or it breaks ReflectUtils.findGenericClassFor().
                session.addMessageHandler(new MessageHandler.Whole<String>()
                {
                    @Override
                    public void onMessage(String message)
                    {
                        textMessages.add(message);
                    }
                });
                openLatch.countDown();
            }

            @Override
            public void onClose(Session session, CloseReason closeReason)
            {
                closeLatch.countDown();
            }
        };

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        Session session = container.connectToServer(clientEndpoint, null, server.getWsUri().resolve("/echo/text"));
        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        session.getBasicRemote().sendText("Echo");

        String resp = textMessages.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("Echo"));
        session.close();
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}
