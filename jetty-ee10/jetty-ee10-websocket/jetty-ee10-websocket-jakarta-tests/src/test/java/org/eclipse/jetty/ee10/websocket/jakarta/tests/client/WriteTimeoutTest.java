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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.client;

import java.util.concurrent.TimeUnit;

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.client.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.websocket.core.exception.WebSocketWriteTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WriteTimeoutTest
{
    @ServerEndpoint("/logSocket")
    public static class ServerSocket extends EventSocket
    {
        @Override
        public void onOpen(Session session, EndpointConfig endpointConfig)
        {
            session.setMaxIdleTimeout(-1);
            session.setMaxTextMessageBufferSize(-1);
            super.onOpen(session, endpointConfig);
        }
    }

    private LocalServer server;
    private JakartaWebSocketContainer client;

    @BeforeEach
    public void start() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(ServerSocket.class);

        client = new JakartaWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testTimeoutOnLargeMessage() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, server.getWsUri().resolve("/logSocket"));

        session.getAsyncRemote().setSendTimeout(5);
        session.setMaxTextMessageBufferSize(1024 * 1024 * 6);

        String string = "xxxxxxx";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < session.getMaxTextMessageBufferSize() - string.length())
        {
            sb.append(string);
        }
        string = sb.toString();

        while (session.isOpen())
        {
            session.getAsyncRemote().sendText(string);
        }

        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientEndpoint.errorLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.error, instanceOf(WebSocketWriteTimeoutException.class));
    }
}
