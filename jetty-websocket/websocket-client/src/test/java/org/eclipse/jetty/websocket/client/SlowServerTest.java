//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.masks.ZeroMasker;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SlowServerTest
{
    private BlockheadServer server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxIdleTimeout(60000);
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testServerSlowToRead() throws Exception
    {
        JettyTrackingSocket tsocket = new JettyTrackingSocket();
        client.setMasker(new ZeroMasker());
        client.setMaxIdleTimeout(60000);

        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(tsocket,wsUri);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // slow down reads
            serverConn.setIncomingFrameConsumer((frame)-> {
                try
                {
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                catch (InterruptedException ignore)
                {
                }
            });

            // Confirm connected
            future.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
            tsocket.waitForConnected();

            int messageCount = 10;

            // Have client write as quickly as it can.
            ClientWriteThread writer = new ClientWriteThread(tsocket.getSession());
            writer.setMessageCount(messageCount);
            writer.setMessage("Hello");
            writer.setSlowness(-1); // disable slowness
            writer.start();
            writer.join();

            // Verify receive
            LinkedBlockingQueue<WebSocketFrame> serverFrames = serverConn.getFrameQueue();
            for(int i=0; i< messageCount; i++)
            {
                WebSocketFrame serverFrame = serverFrames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
                String prefix = "Server Frame[" + i + "]";
                assertThat(prefix, serverFrame, is(notNullValue()));
                assertThat(prefix + ".opCode", serverFrame.getOpCode(), is(OpCode.TEXT));
                assertThat(prefix + ".payload", serverFrame.getPayloadAsUTF8(), is("Hello/" + i + "/"));
            }
        }
    }

    @Test
    public void testServerSlowToSend() throws Exception
    {
        JettyTrackingSocket clientSocket = new JettyTrackingSocket();
        client.setMaxIdleTimeout(60000);

        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        URI wsUri = server.getWsUri();
        Future<Session> clientConnectFuture = client.connect(clientSocket,wsUri);

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Confirm connected
            clientConnectFuture.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
            clientSocket.waitForConnected();

            // Have server write slowly.
            int messageCount = 1000;

            ServerWriteThread writer = new ServerWriteThread(serverConn);
            writer.setMessageCount(messageCount);
            writer.setMessage("Hello");
            writer.setSlowness(10);
            writer.start();
            writer.join();

            // Verify receive
            assertThat("Message Receive Count", clientSocket.messageQueue.size(), is(messageCount));
        }
    }
}
