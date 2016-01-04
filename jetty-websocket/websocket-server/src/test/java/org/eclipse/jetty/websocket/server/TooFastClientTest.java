//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;

/**
 * Test simulating a client that talks too quickly.
 * <p>
 * There is a class of client that will send the GET+Upgrade Request along with a few websocket frames in a single
 * network packet. This test attempts to perform this behavior as close as possible.
 */
public class TooFastClientTest
{
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new MyEchoServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    @Test
    @Ignore("RELEASE")
    public void testUpgradeWithSmallFrames() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();

            // Create ByteBuffer representing the initial opening network packet from the client
            ByteBuffer initialPacket = ByteBuffer.allocate(4096);
            BufferUtil.clearToFill(initialPacket);
            
            // Add upgrade request to packet
            StringBuilder upgradeRequest = client.generateUpgradeRequest();
            ByteBuffer upgradeBuffer = BufferUtil.toBuffer(upgradeRequest.toString(),StandardCharsets.UTF_8);
            initialPacket.put(upgradeBuffer);
            
            // Add text frames
            Generator generator = new Generator(WebSocketPolicy.newClientPolicy(),
                    new LeakTrackingBufferPoolRule("Generator"));
            
            String msg1 = "Echo 1";
            String msg2 = "This is also an echooooo!";
            
            TextFrame frame1 = new TextFrame().setPayload(msg1);
            TextFrame frame2 = new TextFrame().setPayload(msg2);
            
            // Need to set frame mask (as these are client frames)
            byte mask[] = new byte[] { 0x11, 0x22, 0x33, 0x44 };
            frame1.setMask(mask);
            frame2.setMask(mask);

            generator.generateWholeFrame(frame1,initialPacket);
            generator.generateWholeFrame(frame2,initialPacket);

            // Write packet to network
            BufferUtil.flipToFlush(initialPacket,0);
            client.writeRaw(initialPacket);
            
            // Expect upgrade
            client.expectUpgradeResponse();

            // Read frames (hopefully text frames)
            EventQueue<WebSocketFrame> frames = client.readFrames(2,1,TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Text Frame/msg1",tf.getPayloadAsUTF8(),is(msg1));
            tf = frames.poll();
            Assert.assertThat("Text Frame/msg2",tf.getPayloadAsUTF8(),is(msg2));
        }
        finally
        {
            client.close();
        }
    }
    
    /**
     * Test where were a client sends a HTTP Upgrade to websocket AND enough websocket frame(s)
     * to completely overfill the {@link org.eclipse.jetty.io.AbstractConnection#getInputBufferSize()}
     * to test a situation where the WebSocket connection opens with prefill that exceeds 
     * the normal input buffer sizes.
     * @throws Exception on test failure
     */
    @Test
    @Ignore("RELEASE")
    public void testUpgradeWithLargeFrame() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();

            // Create ByteBuffer representing the initial opening network packet from the client
            ByteBuffer initialPacket = ByteBuffer.allocate(100 * 1024);
            BufferUtil.clearToFill(initialPacket);

            // Add upgrade request to packet
            StringBuilder upgradeRequest = client.generateUpgradeRequest();
            ByteBuffer upgradeBuffer = BufferUtil.toBuffer(upgradeRequest.toString(),StandardCharsets.UTF_8);
            initialPacket.put(upgradeBuffer);

            // Add text frames
            Generator generator = new Generator(WebSocketPolicy.newClientPolicy(),
                    new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged()));

            byte bigMsgBytes[] = new byte[64*1024];
            Arrays.fill(bigMsgBytes,(byte)'x');
            String bigMsg = new String(bigMsgBytes, StandardCharsets.UTF_8);
            
            // Need to set frame mask (as these are client frames)
            byte mask[] = new byte[] { 0x11, 0x22, 0x33, 0x44 };
            TextFrame frame = new TextFrame().setPayload(bigMsg);
            frame.setMask(mask);
            generator.generateWholeFrame(frame,initialPacket);

            // Write packet to network
            BufferUtil.flipToFlush(initialPacket,0);
            client.writeRaw(initialPacket);

            // Expect upgrade
            client.expectUpgradeResponse();

            // Read frames (hopefully text frames)
            EventQueue<WebSocketFrame> frames = client.readFrames(1,1,TimeUnit.SECONDS);

            WebSocketFrame tf = frames.poll();
            Assert.assertThat("Text Frame/msg1",tf.getPayloadAsUTF8(),is(bigMsg));
        }
        finally
        {
            client.close();
        }
    }
    
    
}
