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
    /**
     * Echo 0 byte text message
     */
    @Test
    public void testCase1_1_1() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(16);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,0);
            putMask(buf);

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(0));
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 125 byte text message (uses small 7-bit payload length)
     */
    @Test
    public void testCase1_1_2() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte msg[] = new byte[125];
            Arrays.fill(msg,(byte)'*');

            ByteBuffer buf = ByteBuffer.allocate(msg.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,msg.length);
            putMask(buf);
            buf.put(masked(msg));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(msg.length));
            ByteBufferAssert.assertEquals("Text.payload",msg,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 126 byte text message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_3() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte msg[] = new byte[126];
            Arrays.fill(msg,(byte)'*');

            ByteBuffer buf = ByteBuffer.allocate(msg.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,msg.length);
            putMask(buf);
            buf.put(masked(msg));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(msg.length));
            ByteBufferAssert.assertEquals("Text.payload",msg,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 127 byte text message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_4() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte msg[] = new byte[127];
            Arrays.fill(msg,(byte)'*');

            ByteBuffer buf = ByteBuffer.allocate(msg.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,msg.length);
            putMask(buf);
            buf.put(masked(msg));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(msg.length));
            ByteBufferAssert.assertEquals("Text.payload",msg,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 128 byte text message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_5() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte msg[] = new byte[128];
            Arrays.fill(msg,(byte)'*');

            ByteBuffer buf = ByteBuffer.allocate(msg.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,msg.length);
            putMask(buf);
            buf.put(masked(msg));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(msg.length));
            ByteBufferAssert.assertEquals("Text.payload",msg,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 65535 byte text message (uses medium 2 byte payload length)
     */
    @Test
    public void testCase1_1_6() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte msg[] = new byte[65535];
            Arrays.fill(msg,(byte)'*');

            ByteBuffer buf = ByteBuffer.allocate(msg.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,msg.length);
            putMask(buf);
            buf.put(masked(msg));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(msg.length));
            ByteBufferAssert.assertEquals("Text.payload",msg,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Echo 65536 byte text message (uses large 8 byte payload length)
     */
    @Test
    public void testCase1_1_7() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte msg[] = new byte[65536];
            Arrays.fill(msg,(byte)'*');

            ByteBuffer buf = ByteBuffer.allocate(msg.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            buf.put((byte)(0x00 | FIN | OpCode.TEXT.getCode()));
            putPayloadLength(buf,msg.length);
            putMask(buf);
            buf.put(masked(msg));

            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frame should be TEXT frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("Text.payloadLength",frame.getPayloadLength(),is(msg.length));
            ByteBufferAssert.assertEquals("Text.payload",msg,frame.getPayload());
        }
        finally
        {
            client.close();
        }
    }

}
