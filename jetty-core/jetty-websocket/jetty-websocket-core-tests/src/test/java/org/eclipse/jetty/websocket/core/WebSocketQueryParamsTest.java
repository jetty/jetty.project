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

package org.eclipse.jetty.websocket.core;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketQueryParamsTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketCoreClient _client;

    public void start(FrameHandler frameHandler) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler();
        upgradeHandler.addMapping("/", (req, resp, cb) -> frameHandler);
        _server.setHandler(upgradeHandler);
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
    public void testWebSocketQueryParams() throws Exception
    {
        start(new EchoFrameHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession, Callback callback)
            {
                Map<String, List<String>> parameterMap = coreSession.getParameterMap();
                Frame frame = new Frame(OpCode.TEXT, parameterMap.toString());
                coreSession.sendFrame(frame, Callback.NOOP, false);
                super.onOpen(coreSession, callback);
            }
        });

        TestFrameHandler frameHandler = new TestFrameHandler();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort() + "?name1=value1&name2=value2&name2=value3");
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, frameHandler);

        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        Assertions.assertTrue(frameHandler.open.await(5, TimeUnit.SECONDS));

        Frame frame = Objects.requireNonNull(frameHandler.receivedFrames.poll(5, TimeUnit.SECONDS));
        String payload = frame.getPayloadAsUTF8();
        assertThat(payload, containsString("name1=[value1]"));
        assertThat(payload, containsString("name2=[value2, value3]"));

        coreSession.close(Callback.NOOP);
        assertTrue(frameHandler.closed.await(5, TimeUnit.SECONDS));
    }
}
