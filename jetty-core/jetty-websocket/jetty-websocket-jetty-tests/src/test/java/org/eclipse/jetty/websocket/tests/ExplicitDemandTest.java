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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExplicitDemandTest
{
    @WebSocket(autoDemand = false)
    public static class SuspendSocket extends EventSocket
    {
        @Override
        public void onOpen(Session session)
        {
            super.onOpen(session);
            session.demand();
        }

        @Override
        public void onMessage(String message) throws IOException
        {
            super.onMessage(message);
            if (!"suspend".equals(message))
                session.demand();
        }
    }

    private final Server server = new Server();
    private final WebSocketClient client = new WebSocketClient();
    private final SuspendSocket serverSocket = new SuspendSocket();
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
            container.addMapping("/suspend", (rq, rs, cb) -> serverSocket));

        server.setHandler(context);
        server.start();

        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testNoDemandWhenProcessingFrame() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/suspend");
        EventSocket clientSocket = new EventSocket();
        Future<Session> connect = client.connect(clientSocket, uri);
        connect.get(5, TimeUnit.SECONDS);

        clientSocket.session.sendText("suspend", Callback.NOOP);
        clientSocket.session.sendText("suspend", Callback.NOOP);
        clientSocket.session.sendText("hello world", Callback.NOOP);

        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        serverSocket.session.demand();
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("suspend"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        serverSocket.session.demand();
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("hello world"));
        assertNull(serverSocket.textMessages.poll(1, TimeUnit.SECONDS));

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check no errors occurred
        assertNull(clientSocket.error);
        assertNull(serverSocket.error);
    }
}
