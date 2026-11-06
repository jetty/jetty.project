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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DemandTest
{
    ArrayByteBufferPool.Tracking _serverBufferPool;
    Server _server;
    ServerConnector _connector;
    ArrayByteBufferPool.Tracking _clientBufferPool;
    EchoFrameHandler _serverFrameHandler;
    WebSocketCoreClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _serverBufferPool = new ArrayByteBufferPool.Tracking();
        _server = new Server(null, null, _serverBufferPool);
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler(new WebSocketComponents(null, null, _serverBufferPool, null, null));
        _server.setHandler(upgradeHandler);
        upgradeHandler.addMapping("/", (req, resp, cb) -> _serverFrameHandler = new EchoFrameHandler());
        _server.start();

        _clientBufferPool = new ArrayByteBufferPool.Tracking();
        HttpClient httpClient = new HttpClient();
        httpClient.setByteBufferPool(_clientBufferPool);
        _client = new WebSocketCoreClient(httpClient, new WebSocketComponents(null, null, _clientBufferPool, null, null));
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            {
                assertThat("Detected server leaks: " + _serverBufferPool.dumpLeaks(), _serverBufferPool.getLeaks().size(), is(0));
                assertThat("Detected client leaks: " + _clientBufferPool.dumpLeaks(), _clientBufferPool.getLeaks().size(), is(0));
            });
        }
        finally
        {
            LifeCycle.stop(_client);
            LifeCycle.stop(_server);
        }
    }

    public static class AbstractFrameHandler implements FrameHandler
    {
        protected CoreSession _coreSession;

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            _coreSession = coreSession;
            callback.succeeded();
            coreSession.demand();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            callback.succeeded();
            _coreSession.demand();
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            callback.succeeded();
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
                    _coreSession.demand();
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

        // Release the frames stored by the server.
        try (Frame frame = _serverFrameHandler.receivedFrames.poll(5, TimeUnit.SECONDS))
        {
            assertNotNull(frame);
            assertThat(frame.getPayloadAsUTF8(), is("hello world"));
        }
        try (Frame frame = _serverFrameHandler.receivedFrames.poll(5, TimeUnit.SECONDS))
        {
            assertNotNull(frame);
            assertThat(CloseStatus.getCloseStatus(frame).getReason(), is("bad data"));
        }
        assertNull(_serverFrameHandler.receivedFrames.poll());
    }
}
