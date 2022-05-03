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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionTrackingTest
{
    private static BlockingArrayQueue<Session> serverSessions = new BlockingArrayQueue<>();

    @ServerEndpoint("/session-info/{sessionId}")
    public static class SessionTrackingSocket
    {
        @OnOpen
        public void onOpen(Session session)
        {
            serverSessions.offer(session);
        }

        @OnMessage
        public void onMessage(Session session, String msg) throws IOException
        {
            if (msg == null)
            {
                session.getBasicRemote().sendText("Unknown command: <null>");
                return;
            }

            String[] parts = msg.split("\\|");

            if ("openSessions".equals(parts[0]))
            {
                Collection<Session> sessions = session.getOpenSessions();
                String ret = String.format("openSessions(@%s).size=%d", parts[1], sessions.size());
                session.getBasicRemote().sendText(ret);
                return;
            }

            session.getBasicRemote().sendText("Unknown command: " + msg);
        }
    }

    private static LocalServer server;
    private static JakartaWebSocketClientContainer client;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(SessionTrackingSocket.class);

        client = new JakartaWebSocketClientContainer();
        client.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testAddRemoveSessions() throws Exception
    {
        EventSocket clientSocket1 = new EventSocket();
        EventSocket clientSocket2 = new EventSocket();
        EventSocket clientSocket3 = new EventSocket();

        try (Session session1 = client.connectToServer(clientSocket1, server.getWsUri().resolve("/session-info/1")))
        {
            Session serverSession1 = serverSessions.poll(5, TimeUnit.SECONDS);
            assertNotNull(serverSession1);
            sendTextFrameToAll("openSessions|in-1", session1);
            assertThat(clientSocket1.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@in-1).size=1"));

            try (Session session2 = client.connectToServer(clientSocket2, server.getWsUri().resolve("/session-info/2")))
            {
                Session serverSession2 = serverSessions.poll(5, TimeUnit.SECONDS);
                assertNotNull(serverSession2);
                sendTextFrameToAll("openSessions|in-2", session1, session2);
                assertThat(clientSocket1.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@in-2).size=2"));
                assertThat(clientSocket2.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@in-2).size=2"));

                try (Session session3 = client.connectToServer(clientSocket3, server.getWsUri().resolve("/session-info/3")))
                {
                    Session serverSession3 = serverSessions.poll(5, TimeUnit.SECONDS);
                    assertNotNull(serverSession3);
                    sendTextFrameToAll("openSessions|in-3", session1, session2, session3);
                    assertThat(clientSocket1.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@in-3).size=3"));
                    assertThat(clientSocket2.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@in-3).size=3"));
                    assertThat(clientSocket3.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@in-3).size=3"));

                    sendTextFrameToAll("openSessions|lvl-3", session1, session2, session3);
                    assertThat(clientSocket1.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@lvl-3).size=3"));
                    assertThat(clientSocket2.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@lvl-3).size=3"));
                    assertThat(clientSocket3.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@lvl-3).size=3"));

                    // assert session is closed, and we have received the notification from the SessionListener
                    session3.close();
                    assertThat(server.getTrackingListener().getClosedSessions().poll(5, TimeUnit.SECONDS), sameInstance(serverSession3));
                    assertTrue(clientSocket3.closeLatch.await(5, TimeUnit.SECONDS));
                }

                sendTextFrameToAll("openSessions|lvl-2", session1, session2);
                assertThat(clientSocket1.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@lvl-2).size=2"));
                assertThat(clientSocket2.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@lvl-2).size=2"));

                // assert session is closed, and we have received the notification from the SessionListener
                session2.close();
                assertThat(server.getTrackingListener().getClosedSessions().poll(5, TimeUnit.SECONDS), sameInstance(serverSession2));
                assertTrue(clientSocket2.closeLatch.await(5, TimeUnit.SECONDS));
            }

            sendTextFrameToAll("openSessions|lvl-1", session1);
            assertThat(clientSocket1.textMessages.poll(5, TimeUnit.SECONDS), is("openSessions(@lvl-1).size=1"));

            // assert session is closed, and we have received the notification from the SessionListener
            session1.close();
            assertThat(server.getTrackingListener().getClosedSessions().poll(5, TimeUnit.SECONDS), sameInstance(serverSession1));
            assertTrue(clientSocket1.closeLatch.await(5, TimeUnit.SECONDS));
        }
    }

    private static void sendTextFrameToAll(String msg, Session... sessions) throws IOException
    {
        for (Session session : sessions)
        {
            session.getBasicRemote().sendText(msg);
        }
    }
}
