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

package org.eclipse.jetty.embedded;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
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
        webSocketClient.setMaxIdleTimeout(2000);
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
        private static final Logger LOG = Log.getLogger(TrackingClientEndpoint.class);
        public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            messages.offer(message);
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            LOG.warn(cause);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            LOG.debug("Closed({}, {})", statusCode, reason);
        }
    }
}
