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
import java.util.concurrent.LinkedBlockingQueue;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class WebSocketJsrServerTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = WebSocketJsrServer.createServer(0);
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
        WebSocketContainer javaxWebSocketClient = ContainerProvider.getWebSocketContainer();
        javaxWebSocketClient.setDefaultMaxSessionIdleTimeout(2000);
        try
        {
            URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));

            TrackingClientEndpoint clientEndpoint = new TrackingClientEndpoint();

            Session session = javaxWebSocketClient.connectToServer(clientEndpoint, wsUri);
            session.getBasicRemote().sendText("Hello World");

            String response = clientEndpoint.messages.poll(2, SECONDS);
            assertThat("Response", response, is("Hello World"));
        }
        finally
        {
            LifeCycle.stop(javaxWebSocketClient);
        }
    }

    public static class TrackingClientEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        public LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public void onMessage(String message)
        {
            messages.offer(message);
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }

        @Override
        public void onError(Session session, Throwable thr)
        {
            super.onError(session, thr);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            super.onClose(session, closeReason);
        }
    }
}
