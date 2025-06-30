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
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParseFrameAfterCloseTest
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

    public static class ServerFrameHandler extends TestFrameHandler
    {
        private final CountDownLatch connectionCloseLatch = new CountDownLatch(1);
        private boolean first;

        @Override
        public void onOpen(CoreSession coreSession)
        {
            super.onOpen(coreSession);
            first = true;
            ((WebSocketCoreSession)coreSession).getConnection().addEventListener(new Connection.Listener()
            {
                @Override
                public void onClosed(Connection connection)
                {
                    connectionCloseLatch.countDown();
                }
            });
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            super.onFrame(frame, callback);
            if (first)
            {
                coreSession.abort();
                awaitConnectionClose();
                first = false;
            }
        }

        public void awaitConnectionClose()
        {
            try
            {
                connectionCloseLatch.await();
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testParseFrameAfterClose() throws Exception
    {
        ServerFrameHandler serverFrameHandler = new ServerFrameHandler();
        _upgradeHandler.addMapping("/", (req, resp, cb) -> serverFrameHandler);

        TestMessageHandler clientHandler = new TestMessageHandler();
        URI uri = URI.create("ws://localhost:" + _serverConnector.getLocalPort());
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, clientHandler);
        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);

        // Send two frames with batch mode so they are sent in the same write.
        try (Blocker.Callback callback = Blocker.callback())
        {
            coreSession.sendFrame(new Frame(OpCode.TEXT, "message1"), Callback.NOOP, true);
            coreSession.sendFrame(new Frame(OpCode.TEXT, "message2"), Callback.NOOP, true);
            coreSession.flush(callback);
            callback.block();
        }

        // We get first frame before abort.
        Frame frame = Objects.requireNonNull(serverFrameHandler.getFrames().poll(5, TimeUnit.SECONDS));
        assertThat(frame.getOpCode(), equalTo(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), equalTo("message1"));

        // The connection is aborted after the first frame, but the second frame is still read as it was in the network buffer.
        frame = Objects.requireNonNull(serverFrameHandler.getFrames().poll(5, TimeUnit.SECONDS));
        assertThat(frame.getOpCode(), equalTo(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), equalTo("message2"));

        // Close connection.
        coreSession.close(Callback.NOOP);
        assertTrue(clientHandler.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.closeStatus.getCode(), equalTo(CloseStatus.NO_CLOSE));
    }
}
