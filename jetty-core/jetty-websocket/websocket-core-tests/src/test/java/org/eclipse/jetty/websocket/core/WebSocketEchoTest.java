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
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketEchoTest
{
    private Server _server;
    private WebSocketUpgradeHandler _upgradeHandler;
    private WebSocketCoreClient _client;
    private ServerConnector _serverConnector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _serverConnector = new ServerConnector(_server);
        _server.addConnector(_serverConnector);

        _upgradeHandler = new WebSocketUpgradeHandler();
        _server.setHandler(_upgradeHandler);
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
    public void test() throws Exception
    {
        _upgradeHandler.addMapping("/", WebSocketNegotiator.from(n -> new EchoFrameHandler()));
        TestMessageHandler clientHandler = new TestMessageHandler();
        URI uri = URI.create("ws://localhost:" + _serverConnector.getLocalPort());
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, clientHandler);
        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);

        // Send "hello world" text frame.
        try (Blocking.Callback callback = Blocking.callback())
        {
            coreSession.sendFrame(new Frame(OpCode.TEXT, "hello world"), callback, false);
            callback.block();
        }

        // Receive echoed frame.
        String receivedMessage = clientHandler.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(receivedMessage, equalTo("hello world"));

        // Close connection.
        coreSession.close(Callback.NOOP);
        assertTrue(clientHandler.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.closeStatus.getCode(), equalTo(CloseStatus.NO_CODE));
    }
}
