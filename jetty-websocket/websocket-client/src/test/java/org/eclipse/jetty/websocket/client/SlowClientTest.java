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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SlowClientTest
{
    private static BlockheadServer server;
    private WebSocketClient client;

    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getPolicy().setIdleTimeout(60000);
        client.start();
    }

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testClientSlowToSend() throws Exception
    {
        JettyTrackingSocket tsocket = new JettyTrackingSocket();
        client.getPolicy().setIdleTimeout(60000);

        URI wsUri = server.getWsUri();
        Future<Session> future = client.connect(tsocket, wsUri);

        CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
        server.addConnectFuture(serverConnFut);

        // Confirm connected
        future.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT);
        tsocket.waitForConnected();

        int messageCount = 10;

        try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Have client write slowly.
            ClientWriteThread writer = new ClientWriteThread(tsocket.getSession());
            writer.setMessageCount(messageCount);
            writer.setMessage("Hello");
            writer.setSlowness(10);
            writer.start();
            writer.join();

            LinkedBlockingQueue<WebSocketFrame> serverFrames = serverConn.getFrameQueue();

            for (int i = 0; i < messageCount; i++)
            {
                WebSocketFrame serverFrame = serverFrames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
                String prefix = "Server frame[" + i + "]";
                assertThat(prefix + ".opcode", serverFrame.getOpCode(), is(OpCode.TEXT));
                assertThat(prefix + ".payload", serverFrame.getPayloadAsUTF8(), is("Hello/" + i + "/"));
            }

            // Close
            tsocket.getSession().close(StatusCode.NORMAL, "Done");

            // confirm close received on server
            WebSocketFrame serverFrame = serverFrames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("close frame", serverFrame.getOpCode(), is(OpCode.CLOSE));
            CloseInfo closeInfo = new CloseInfo(serverFrame);
            assertThat("close info", closeInfo.getStatusCode(), is(StatusCode.NORMAL));
            WebSocketFrame respClose = WebSocketFrame.copy(serverFrame);
            respClose.setMask(null); // remove client mask (if present)
            serverConn.write(respClose);

            // Verify server response
            Assert.assertTrue("Client Socket Closed", tsocket.closeLatch.await(3, TimeUnit.MINUTES));
            tsocket.assertCloseCode(StatusCode.NORMAL);
        }
    }
}
