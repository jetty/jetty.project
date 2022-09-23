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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemandTest
{
    Server _server;
    ServerConnector _connector;
    WebSocketCoreClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler();
        _server.setHandler(upgradeHandler);
        upgradeHandler.addMapping("/", (req, resp, cb) -> new EchoFrameHandler());
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

    public static class AbstractFrameHandler implements FrameHandler
    {
        protected CoreSession _coreSession;

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            _coreSession = coreSession;
            callback.succeeded();
            coreSession.demand(1);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            callback.succeeded();
            _coreSession.demand(1);
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            callback.succeeded();
            _coreSession.demand(1);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public boolean isDemanding()
        {
            return true;
        }
    }

    @Test
    public void testDemandAfterClose() throws Exception
    {
        CountDownLatch closed = new CountDownLatch(1);
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        AbstractFrameHandler frameHandler = new AbstractFrameHandler()
        {
            @Override
            public void onFrame(Frame frame, Callback callback)
            {
                try
                {
                    // Fail the core session so it is completely closed.
                    FutureCallback futureCallback = new FutureCallback();
                    _coreSession.close(CloseStatus.BAD_PAYLOAD, "bad data", futureCallback);
                    futureCallback.block();
                    _coreSession.abort();

                    // Demand should not throw even if closed.
                    _coreSession.demand(1);
                    errorFuture.complete(null);
                }
                catch (Throwable t)
                {
                    errorFuture.complete(t);
                }
            }

            @Override
            public void onClosed(CloseStatus closeStatus, Callback callback)
            {
                super.onClosed(closeStatus, callback);
                closed.countDown();
            }
        };

        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        CoreSession coreSession = _client.connect(frameHandler, uri).get(5, TimeUnit.SECONDS);
        coreSession.sendFrame(new Frame(OpCode.TEXT, "hello world"), Callback.NOOP, false);
        assertTrue(closed.await(5, TimeUnit.SECONDS));

        // There should be no error from the frame handler.
        Throwable error = errorFuture.get(5, TimeUnit.SECONDS);
        assertNull(error);
    }
}
