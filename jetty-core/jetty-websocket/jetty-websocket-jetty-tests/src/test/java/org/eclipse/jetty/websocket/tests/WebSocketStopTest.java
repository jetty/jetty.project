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

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketStopTest
{
    private final Server server = new Server();
    private final WebSocketClient client = new WebSocketClient();
    private final EventSocket serverSocket = new EventSocket();
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, container ->
            container.addMapping("/", (rq, rs, cb) -> serverSocket));

        server.setHandler(wsHandler);
        server.start();

        client.setStopTimeout(5000);
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void stopWithOpenSessions() throws Exception
    {
        final URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");

        // Connect to two sessions to the server.
        EventSocket clientSocket1 = new EventSocket();
        EventSocket clientSocket2 = new EventSocket();
        assertNotNull(client.connect(clientSocket1, uri).get(5, TimeUnit.SECONDS));
        assertNotNull(client.connect(clientSocket2, uri).get(5, TimeUnit.SECONDS));
        assertTrue(clientSocket1.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSocket2.openLatch.await(5, TimeUnit.SECONDS));

        // WS client is stopped and closes sessions with SHUTDOWN code.
        client.stop();
        assertTrue(clientSocket1.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSocket2.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket1.closeCode, is(StatusCode.SHUTDOWN));
        assertThat(clientSocket2.closeCode, is(StatusCode.SHUTDOWN));
    }

    @Test
    public void testWriteAfterStop() throws Exception
    {
        URI uri = new URI("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket clientSocket = new EventSocket();

        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("permessage-deflate");
        Session session = client.connect(clientSocket, uri, upgradeRequest).get(5, TimeUnit.SECONDS);
        clientSocket.session.sendText("init deflater", Callback.NOOP);
        assertThat(serverSocket.textMessages.poll(5, TimeUnit.SECONDS), is("init deflater"));
        session.close(StatusCode.NORMAL, null, Callback.NOOP);

        // make sure both sides are closed
        clientSocket.session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));

        // check we closed normally
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
        assertThat(serverSocket.closeCode, is(StatusCode.NORMAL));

        ExecutionException error = assertThrows(ExecutionException.class, () ->
            Callback.Completable.with(c -> session.sendText("this should fail before ExtensionStack", c))
                .get(5, TimeUnit.SECONDS)
        );
        assertThat(error.getCause(), instanceOf(ClosedChannelException.class));
    }
}
