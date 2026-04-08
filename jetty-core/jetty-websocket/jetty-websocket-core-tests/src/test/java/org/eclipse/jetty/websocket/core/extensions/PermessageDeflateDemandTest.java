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

package org.eclipse.jetty.websocket.core.extensions;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.TestMessageHandler;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PermessageDeflateDemandTest
{
    private Server _server;
    private ArrayByteBufferPool.Tracking _bufferPool;
    private WebSocketCoreClient _client;
    private ServerConnector _connector;
    private WebSocketUpgradeHandler _upgradeHandler;
    private WebSocketComponents _components;

    @BeforeEach
    public void before() throws Exception
    {
        _bufferPool = new ArrayByteBufferPool.Tracking();
        _server = new Server(null, null, _bufferPool);
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _components = new WebSocketComponents();
        _upgradeHandler = new WebSocketUpgradeHandler(_components);
        _server.setHandler(_upgradeHandler);
        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        try
        {
            assertThat("Detected leaks: " + _bufferPool.dumpLeaks(), _bufferPool.getLeaks().size(), is(0));
        }
        finally
        {
            LifeCycle.stop(_client);
            LifeCycle.stop(_server);
        }
    }

    @Test
    public void testManyIncomingFrames() throws Exception
    {
        TestMessageHandler serverHandler = new TestMessageHandler();
        _upgradeHandler.addMapping("/", (req, resp, cb) -> serverHandler);

        TestFrameHandler clientHandler = new TestFrameHandler();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate");

        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        assertNotNull(coreSession);
        // Set max frame size to autoFragment the message into multiple frames.
        ByteBuffer message = randomBytes(1024);
        ByteBuffer messageSlice = message.slice();
        coreSession.setMaxFrameSize(64);
        coreSession.sendFrame(new Frame(OpCode.BINARY, message).setFin(true), Callback.NOOP, false);

        coreSession.close(CloseStatus.NORMAL, null, Callback.NOOP);
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.closeStatus.getCode(), equalTo(CloseStatus.NORMAL));

        assertThat(serverHandler.binaryMessages.size(), equalTo(1));
        ByteBuffer recvMessage = serverHandler.binaryMessages.poll();
        assertThat(message.remaining(), equalTo(0));
        assertThat(recvMessage, equalTo(messageSlice));
    }

    @Test
    public void testExternalDemand() throws Exception
    {
        _components.getExtensionRegistry().register("@test", MyExtension.class);
        TestMessageHandler serverHandler = new TestMessageHandler(false);
        _upgradeHandler.addMapping("/", (req, resp, cb) ->
        {
            List<ExtensionConfig> extensions = resp.getExtensions();
            extensions.add(0, ExtensionConfig.parse("@test"));
            resp.setExtensions(extensions);
            return serverHandler;
        });

        try
        {
            TestFrameHandler clientHandler = new TestFrameHandler();
            URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
            CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, clientHandler);
            upgradeRequest.addExtensions("permessage-deflate");

            CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
            assertNotNull(coreSession);
            ByteBuffer message = randomBytes(1024);

            // Send the same message twice.
            coreSession.sendFrame(new Frame(OpCode.BINARY, message.slice()).setFin(true), Callback.NOOP, false);
            coreSession.sendFrame(new Frame(OpCode.BINARY, message.slice()).setFin(true), Callback.NOOP, false);

            // If we demand we receive the first message.
            serverHandler.getCoreSession().demand();
            ByteBuffer recvMessage = serverHandler.binaryMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(recvMessage);
            assertThat(recvMessage, equalTo(message));

            // Delay demanding until we have returned from the permessage-deflate extension.
            await().atMost(5, TimeUnit.SECONDS).until(() -> MyExtension.COUNT.get() == 0);
            serverHandler.getCoreSession().demand();
            recvMessage = serverHandler.binaryMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(recvMessage);
            assertThat(recvMessage, equalTo(message));

            // Assert that the session can be closed normally.
            serverHandler.getCoreSession().demand();
            coreSession.close(CloseStatus.NORMAL, null, Callback.NOOP);
            assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
            assertThat(clientHandler.closeStatus.getCode(), equalTo(CloseStatus.NORMAL));
        }
        catch (Throwable t)
        {
            Throwable serverError = serverHandler.error;
            if (serverError != null)
                serverError.printStackTrace(System.err);
            throw t;
        }
    }

    public static class MyExtension extends AbstractExtension
    {
        static final AtomicInteger COUNT =  new AtomicInteger(0);

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            COUNT.incrementAndGet();
            super.onFrame(frame, callback);
            COUNT.decrementAndGet();
        }
    }

    private static ByteBuffer randomBytes(int size)
    {
        var bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return BufferUtil.toBuffer(bytes);
    }
}
