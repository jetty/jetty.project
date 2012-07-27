// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server.ab;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.ByteBufferAssert;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase1 extends AbstractABCase
{
    private void assertEchoEmptyFrame(OpCode opcode) throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | opcode.getCode()));
            putPayloadLength(buf,0);
            putMask(buf);

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be " + opcode + " frame",frame.getOpCode(),is(opcode));
            Assert.assertThat(opcode + ".payloadLength",frame.getPayloadLength(),is(0));
        }
        finally
        {
            client.close();
        }
    }

    private void assertEchoFrame(OpCode opcode, byte[] payload) throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(payload.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | opcode.getCode()));
            putPayloadLength(buf,payload.length);
            putMask(buf);
            buf.put(masked(payload));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be " + opcode + " frame",frame.getOpCode(),is(opcode));
            Assert.assertThat(opcode + ".payloadLength",frame.getPayloadLength(),is(payload.length));
            ByteBufferAssert.assertEquals(opcode + ".payload",payload,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    private void assertEchoSegmentedFrame(OpCode opcode, byte payload[], int segmentSize) throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(payload.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | opcode.getCode()));
            putPayloadLength(buf,payload.length);
            putMask(buf);
            buf.put(masked(payload));

            BufferUtil.flipToFlush(buf,0);
            int origLimit = buf.limit();
            int limit = buf.limit();
            int len;
            int pos = buf.position();
            int overallLeft = buf.remaining();
            while (overallLeft > 0)
            {
                buf.position(pos);
                limit = Math.min(origLimit,pos + segmentSize);
                buf.limit(limit);
                len = buf.remaining();
                overallLeft -= len;
                pos += len;
                client.writeRaw(buf);
                client.flush();
            }

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be " + opcode + " frame",frame.getOpCode(),is(opcode));
            Assert.assertThat(opcode + ".payloadLength",frame.getPayloadLength(),is(payload.length));
            ByteBufferAssert.assertEquals(opcode + ".payload",payload,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 0 byte TEXT message
     */
    @Test
    public void testCase1_1_1() throws Exception
    {
        assertEchoEmptyFrame(OpCode.TEXT);
    }

    /**
     * Echo 125 byte TEXT message (uses small 7-bit payload length)
     */
    @Test
    public void testCase1_1_2() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload,(byte)'*');

        assertEchoFrame(OpCode.TEXT,payload);
    }

    /**
     * Echo 126 byte TEXT message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_3() throws Exception
    {
        byte payload[] = new byte[126];
        Arrays.fill(payload,(byte)'*');

        assertEchoFrame(OpCode.TEXT,payload);
    }

    /**
     * Echo 127 byte TEXT message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_4() throws Exception
    {
        byte payload[] = new byte[127];
        Arrays.fill(payload,(byte)'*');

        assertEchoFrame(OpCode.TEXT,payload);
    }

    /**
     * Echo 128 byte TEXT message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_5() throws Exception
    {
        byte payload[] = new byte[128];
        Arrays.fill(payload,(byte)'*');

        assertEchoFrame(OpCode.TEXT,payload);
    }

    /**
     * Echo 65535 byte TEXT message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_6() throws Exception
    {
        byte payload[] = new byte[65535];
        Arrays.fill(payload,(byte)'*');

        assertEchoFrame(OpCode.TEXT,payload);
    }

    /**
     * Echo 65536 byte TEXT message (uses large 8 byte payload length)
     */
    @Test
    public void testCase1_1_7() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)'*');

        assertEchoFrame(OpCode.TEXT,payload);
    }

    /**
     * Echo 65536 byte TEXT message (uses large 8 byte payload length).
     * <p>
     * Only send 1 TEXT frame from client, but in small segments (flushed after each).
     * <p>
     * This is done to test the parsing together of the frame on the server side.
     */
    @Test
    public void testCase1_1_8() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)'*');
        int segmentSize = 997;

        assertEchoSegmentedFrame(OpCode.TEXT,payload,segmentSize);
    }

    /**
     * Echo 0 byte BINARY message
     */
    @Test
    public void testCase1_2_1() throws Exception
    {
        assertEchoEmptyFrame(OpCode.BINARY);
    }

    /**
     * Echo 125 byte BINARY message (uses small 7-bit payload length)
     */
    @Test
    public void testCase1_2_2() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload,(byte)0xFE);

        assertEchoFrame(OpCode.BINARY,payload);
    }

    /**
     * Echo 126 byte BINARY message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_2_3() throws Exception
    {
        byte payload[] = new byte[126];
        Arrays.fill(payload,(byte)0xFE);

        assertEchoFrame(OpCode.BINARY,payload);
    }

    /**
     * Echo 127 byte BINARY message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_2_4() throws Exception
    {
        byte payload[] = new byte[127];
        Arrays.fill(payload,(byte)0xFE);

        assertEchoFrame(OpCode.BINARY,payload);
    }

    /**
     * Echo 128 byte BINARY message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_2_5() throws Exception
    {
        byte payload[] = new byte[128];
        Arrays.fill(payload,(byte)0xFE);

        assertEchoFrame(OpCode.BINARY,payload);
    }

    /**
     * Echo 65535 byte BINARY message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_2_6() throws Exception
    {
        byte payload[] = new byte[65535];
        Arrays.fill(payload,(byte)0xFE);

        assertEchoFrame(OpCode.BINARY,payload);
    }

    /**
     * Echo 65536 byte BINARY message (uses large 8 byte payload length)
     */
    @Test
    public void testCase1_2_7() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)0xFE);

        assertEchoFrame(OpCode.BINARY,payload);
    }

    /**
     * Echo 65536 byte BINARY message (uses large 8 byte payload length).
     * <p>
     * Only send 1 BINARY frame from client, but in small segments (flushed after each).
     * <p>
     * This is done to test the parsing together of the frame on the server side.
     */
    @Test
    public void testCase1_2_8() throws Exception
    {
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)0xFE);
        int segmentSize = 997;

        assertEchoSegmentedFrame(OpCode.BINARY,payload,segmentSize);
    }
}
