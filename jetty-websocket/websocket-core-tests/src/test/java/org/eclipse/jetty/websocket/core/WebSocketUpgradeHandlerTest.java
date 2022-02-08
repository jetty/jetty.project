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

package org.eclipse.jetty.websocket.core;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketUpgradeHandlerTest
{
    private Server server;
    private WebSocketCoreClient client;
    private URI serverUri;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler();
        upgradeHandler.addMapping("/path/echo", new TestWebSocketNegotiator(new EchoFrameHandler()));
        server.setHandler(upgradeHandler);
        server.start();

        client = new WebSocketCoreClient();
        client.start();

        serverUri = URI.create("ws://localhost:" + connector.getLocalPort());
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
        client.stop();
    }

    @Test
    public void testUpgradeByWebSocketUpgradeHandler() throws Exception
    {
        TestMessageHandler clientEndpoint = new TestMessageHandler();
        CoreSession coreSession = client.connect(clientEndpoint, serverUri.resolve("/path/echo")).get(5, TimeUnit.SECONDS);
        assertNotNull(coreSession);
        coreSession.close(Callback.NOOP);
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
