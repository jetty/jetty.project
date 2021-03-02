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

package org.eclipse.jetty.websocket.core.client;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketServer;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketClientExtensionTest
{
    private WebSocketServer server;
    private URI serverUri;

    private WebSocketCoreClient client;

    public static class MyExtension1 extends AbstractExtension
    {
        @Override
        public void init(ExtensionConfig config, WebSocketComponents components)
        {
            System.err.println("myExtension init()");
            super.init(config, components);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            System.err.println("myExtension onFrame()");
            super.onFrame(frame, callback);
        }

        @Override
        public void sendFrame(Frame frame, Callback callback, boolean batch)
        {
            System.err.println("myExtension sendFrame()");
            super.sendFrame(frame, callback, batch);
        }
    }

    @BeforeEach
    public void setup() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();
        server = new WebSocketServer(serverHandler);
        server.start();
        serverUri = new URI("ws://localhost:" + server.getLocalPort());

        server.getComponents().getExtensionRegistry().register("ext1", MyExtension1.class);

        client = new WebSocketCoreClient();
        client.start();
    }

    @AfterEach
    public void cleanup() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testExtensionNotAvailableOnClient() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(client, serverUri, clientHandler);

        // The client connects requesting an extensions which does not exist.
        upgradeRequest.addExtensions("ext1");
        CompletableFuture<CoreSession> connect = client.connect(upgradeRequest);

        // Connection was able to be opened.
        assertNotNull(connect.get(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.open.await(5, TimeUnit.SECONDS));

        // We have the requested extension was negotiated even though we don't have it implemented on the client side.
        List<ExtensionConfig> negotiatedExtensions = clientHandler.getCoreSession().getNegotiatedExtensions();
        assertTrue(negotiatedExtensions.contains(ExtensionConfig.parse("ext1")));
    }
}
