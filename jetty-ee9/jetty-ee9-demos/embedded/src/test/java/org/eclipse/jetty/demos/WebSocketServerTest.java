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

package org.eclipse.jetty.demos;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WebSocketServerTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = WebSocketServer.createServer(0);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetEcho() throws Exception
    {
        WebSocketClient webSocketClient = new WebSocketClient();
        webSocketClient.setIdleTimeout(Duration.ofSeconds(2));
        try
        {
            webSocketClient.start();
            URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));

            TrackingClientEndpoint clientEndpoint = new TrackingClientEndpoint();

            Future<Session> sessionFut = webSocketClient.connect(clientEndpoint, wsUri);
            Session session = sessionFut.get(2, SECONDS);
            session.getRemote().sendString("Hello World");

            String response = clientEndpoint.messages.poll(2, SECONDS);
            assertThat("Response", response, is("Hello World"));
        }
        finally
        {
            LifeCycle.stop(webSocketClient);
        }
    }

    @WebSocket
    public static class TrackingClientEndpoint
    {
        private static final Logger LOG = LoggerFactory.getLogger(TrackingClientEndpoint.class);
        public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            messages.offer(message);
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            LOG.warn("TrackingClientEndpoint Error", cause);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            LOG.debug("Closed({}, {})", statusCode, reason);
        }
    }
}
