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

package org.eclipse.jetty.websocket.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.BlockheadClientRequest;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test simulating a client that talks too quickly.
 * <p>
 * There is a class of client that will send the GET+Upgrade Request along with a few websocket frames in a single
 * network packet. This test attempts to perform this behavior as close as possible.
 */
public class TooFastClientTest
{
    private static SimpleServletServer server;
    private static BlockheadClient client;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new MyEchoServlet());
        server.start();
    }

    @AfterAll
    public static void stopServer()
    {
        server.stop();
    }

    @BeforeAll
    public static void startClient() throws Exception
    {
        client = new BlockheadClient();
        client.setIdleTimeout(TimeUnit.SECONDS.toMillis(2));
        client.start();
    }

    @AfterAll
    public static void stopClient() throws Exception
    {
        client.stop();
    }

    private ByteBuffer createInitialPacket(String... msgs)
    {
        int len = Arrays.stream(msgs).mapToInt((str) -> str.length() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer initialPacket = ByteBuffer.allocate(len);

        BufferUtil.clearToFill(initialPacket);
        Generator generator = new Generator(WebSocketPolicy.newClientPolicy(),
            new MappedByteBufferPool());

        for (String msg : msgs)
        {
            TextFrame frame = new TextFrame().setPayload(msg);
            byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
            frame.setMask(mask);
            generator.generateWholeFrame(frame, initialPacket);
        }

        BufferUtil.flipToFlush(initialPacket, 0);
        return initialPacket;
    }

    @Test
    public void testUpgradeWithSmallFrames() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());

        String msg1 = "Echo 1";
        String msg2 = "This is also an echooooo!";

        ByteBuffer initialPacket = createInitialPacket(msg1, msg2);
        request.setInitialBytes(initialPacket);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Read frames (hopefully text frames)
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();
            WebSocketFrame tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Text Frame/msg1", tf.getPayloadAsUTF8(), is(msg1));
            tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Text Frame/msg2", tf.getPayloadAsUTF8(), is(msg2));
        }
    }

    /**
     * Test where were a client sends an HTTP Upgrade to websocket AND enough websocket frame(s)
     * to completely overfill the {@link org.eclipse.jetty.io.AbstractConnection#getInputBufferSize()}
     * to test a situation where the WebSocket connection opens with prefill that exceeds
     * the normal input buffer sizes.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testUpgradeWithLargeFrame() throws Exception
    {
        BlockheadClientRequest request = client.newWsRequest(server.getServerUri());

        byte[] bigMsgBytes = new byte[64 * 1024];
        Arrays.fill(bigMsgBytes, (byte)'x');
        String bigMsg = new String(bigMsgBytes, StandardCharsets.UTF_8);

        ByteBuffer initialPacket = createInitialPacket(bigMsg);
        request.setInitialBytes(initialPacket);

        Future<BlockheadConnection> connFut = request.sendAsync();

        try (BlockheadConnection clientConn = connFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
        {
            // Read frames (hopefully text frames)
            LinkedBlockingQueue<WebSocketFrame> frames = clientConn.getFrameQueue();

            WebSocketFrame tf = frames.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
            assertThat("Text Frame/msg1", tf.getPayloadAsUTF8(), is(bigMsg));
        }
    }
}
