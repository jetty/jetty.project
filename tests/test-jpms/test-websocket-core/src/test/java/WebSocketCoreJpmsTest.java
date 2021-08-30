//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.example.websocket.MyFrameHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class WebSocketCoreJpmsTest
{
    private Server _server;
    private ServerConnector _serverConnector;
    private WebSocketCoreClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _serverConnector = new ServerConnector(_server);
        _server.addConnector(_serverConnector);

        WebSocketUpgradeHandler webSocketUpgradeHandler = new WebSocketUpgradeHandler();
        FrameHandler myFrameHandler = new MyFrameHandler("Server");
        webSocketUpgradeHandler.addMapping("/ws", WebSocketNegotiator.from(negotiation -> myFrameHandler));

        _server.setHandler(webSocketUpgradeHandler);
        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testSimpleEcho() throws Exception
    {
        MyFrameHandler frameHandler = new MyFrameHandler("Client");
        URI uri = URI.create("ws://localhost:" + _serverConnector.getLocalPort() + "/ws");
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, frameHandler);
        upgradeRequest.addExtensions("permessage-deflate");
        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        coreSession.close(Callback.NOOP);
    }
}
