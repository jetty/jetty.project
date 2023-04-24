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
import java.nio.channels.WritePendingException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.AbstractExtension;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.util.FutureCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MaxOutgoingFramesTest
{
    public static CountDownLatch outgoingBlocked;
    public static CountDownLatch firstFrameBlocked;

    private final EventSocket serverSocket = new EventSocket();
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;

    @BeforeEach
    public void start() throws Exception
    {
        outgoingBlocked = new CountDownLatch(1);
        firstFrameBlocked = new CountDownLatch(1);

        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
        {
            container.addMapping("/", (rq, rs, cb) -> serverSocket);
            container.getWebSocketComponents().getExtensionRegistry().register(BlockingOutgoingExtension.class.getName(), BlockingOutgoingExtension.class);
        });

        server.setHandler(context);
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        outgoingBlocked.countDown();
        server.stop();
        client.stop();
    }

    public static class BlockingOutgoingExtension extends AbstractExtension
    {
        @Override
        public String getName()
        {
            return BlockingOutgoingExtension.class.getName();
        }

        @Override
        public void sendFrame(Frame frame, org.eclipse.jetty.util.Callback callback, boolean batch)
        {
            try
            {
                firstFrameBlocked.countDown();
                outgoingBlocked.await();
                super.sendFrame(frame, callback, batch);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static class CountingCallback implements Callback
    {
        private final CountDownLatch successes;

        public CountingCallback(int count)
        {
            successes = new CountDownLatch(count);
        }

        @Override
        public void succeed()
        {
            successes.countDown();
        }

        @Override
        public void fail(Throwable t)
        {
            t.printStackTrace();
        }
    }

    @Test
    public void testMaxOutgoingFrames() throws Exception
    {
        // We need to have the frames queued but not yet sent, we do this by blocking in the ExtensionStack.
        WebSocketCoreClient coreClient = client.getBean(WebSocketCoreClient.class);
        coreClient.getExtensionRegistry().register(BlockingOutgoingExtension.class.getName(), BlockingOutgoingExtension.class);

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions(BlockingOutgoingExtension.class.getName());
        client.connect(socket, uri, upgradeRequest).get(5, TimeUnit.SECONDS);
        assertTrue(socket.openLatch.await(5, TimeUnit.SECONDS));

        int numFrames = 30;
        socket.session.setMaxOutgoingFrames(numFrames);

        // Verify that we can send up to numFrames without any problem.
        // First send will block in the Extension so it needs to be done in new thread, others frames will be queued.
        CountingCallback countingCallback = new CountingCallback(numFrames);
        new Thread(() -> socket.session.sendText("0", countingCallback)).start();
        assertTrue(firstFrameBlocked.await(5, TimeUnit.SECONDS));
        for (int i = 1; i < numFrames; i++)
        {
            socket.session.sendText(Integer.toString(i), countingCallback);
        }

        // Sending any more frames will result in WritePendingException.
        FutureCallback callback = new FutureCallback();
        socket.session.sendText("fail", callback);
        ExecutionException executionException = assertThrows(ExecutionException.class, () -> callback.get(5, TimeUnit.SECONDS));
        assertThat(executionException.getCause(), instanceOf(WritePendingException.class));

        // Check that all callbacks are succeeded when the server processes the frames.
        outgoingBlocked.countDown();
        assertTrue(countingCallback.successes.await(5, TimeUnit.SECONDS));

        // Close successfully.
        socket.session.close();
        assertTrue(serverSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
    }
}
