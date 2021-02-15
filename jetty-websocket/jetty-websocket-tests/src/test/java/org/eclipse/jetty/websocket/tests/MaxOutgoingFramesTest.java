//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.nio.channels.WritePendingException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
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

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        NativeWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.addMapping("/", (req, resp) -> serverSocket);
            container.getFactory().getExtensionFactory().register(BlockingOutgoingExtension.class.getName(), BlockingOutgoingExtension.class);
        });

        WebSocketUpgradeFilter.configure(contextHandler);
        server.setHandler(contextHandler);

        client = new WebSocketClient();
        server.start();
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
        public void incomingFrame(Frame frame)
        {
            getNextIncoming().incomingFrame(frame);
        }

        @Override
        public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
        {
            try
            {
                firstFrameBlocked.countDown();
                outgoingBlocked.await();
                getNextOutgoing().outgoingFrame(frame, callback, batchMode);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public static class CountingCallback implements WriteCallback
    {
        private final CountDownLatch successes;

        public CountingCallback(int count)
        {
            successes = new CountDownLatch(count);
        }

        @Override
        public void writeSuccess()
        {
            successes.countDown();
        }

        @Override
        public void writeFailed(Throwable t)
        {
            t.printStackTrace();
        }
    }

    @Test
    public void testMaxOutgoingFrames() throws Exception
    {
        // We need to have the frames queued but not yet sent, we do this by blocking in the ExtensionStack.
        client.getExtensionFactory().register(BlockingOutgoingExtension.class.getName(), BlockingOutgoingExtension.class);

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket socket = new EventSocket();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions(BlockingOutgoingExtension.class.getName());
        client.connect(socket, uri, upgradeRequest).get(5, TimeUnit.SECONDS);
        assertTrue(socket.openLatch.await(5, TimeUnit.SECONDS));

        int numFrames = 30;
        RemoteEndpoint remote = socket.session.getRemote();
        remote.setMaxOutgoingFrames(numFrames);

        // Verify that we can send up to numFrames without any problem.
        // First send will block in the Extension so it needs to be done in new thread, others frames will be queued.
        CountingCallback countingCallback = new CountingCallback(numFrames);
        new Thread(() -> remote.sendString("0", countingCallback)).start();
        assertTrue(firstFrameBlocked.await(5, TimeUnit.SECONDS));
        for (int i = 1; i < numFrames; i++)
        {
            remote.sendString(Integer.toString(i), countingCallback);
        }

        // Sending any more frames will result in WritePendingException.
        FutureWriteCallback callback = new FutureWriteCallback();
        remote.sendString("fail", callback);
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
