package org.eclipse.jetty.websocket.server.ab;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.ByteBufferAssert;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.helper.IncomingFramesCapture;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase2 extends AbstractABCase
{
    private void assertPingFrame(byte[] payload) throws Exception
    {
        boolean hasPayload = ((payload != null) && (payload.length > 0));

        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            int len = 0;
            if (hasPayload)
            {
                len = payload.length;
            }

            ByteBuffer buf = ByteBuffer.allocate(len + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            // Prepare PING Frame
            buf.put((byte)(0x00 | FIN | OpCode.PING.getCode()));
            putPayloadLength(buf,len);
            putMask(buf);
            if (hasPayload)
            {
                buf.put(masked(payload));
            }

            // Prepare CLOSE Frame
            buf.put((byte)(0x00 | FIN | OpCode.CLOSE.getCode()));
            putPayloadLength(buf,2);
            putMask(buf);
            buf.put(masked(new byte[]
            { 0x03, (byte)0xE8 }));

            // Write Data Frame
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);
            client.flush();

            // Read frames
            IncomingFramesCapture capture = client.readFrames(2,TimeUnit.MILLISECONDS,500);

            // Validate echo'd frame
            WebSocketFrame frame = capture.getFrames().get(0);
            Assert.assertThat("frame should be PONG frame",frame.getOpCode(),is(OpCode.PONG));
            if (hasPayload)
            {
                Assert.assertThat("PONG.payloadLength",frame.getPayloadLength(),is(payload.length));
                ByteBufferAssert.assertEquals("PONG.payload",payload,frame.getPayload());
            }
            else
            {
                Assert.assertThat("PONG.payloadLength",frame.getPayloadLength(),is(0));
            }

            // Validate close
            frame = capture.getFrames().get(1);
            Assert.assertThat("CLOSE.frame.opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("CLOSE.statusCode",close.getStatusCode(),is(StatusCode.NORMAL));
        }
        finally
        {
            client.disconnect();
        }
    }

    private void assertProtocolError(byte[] payload) throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(payload.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            // Prepare PING Frame
            buf.put((byte)(0x00 | FIN | OpCode.PING.getCode()));
            putPayloadLength(buf,payload.length);
            putMask(buf);
            buf.put(masked(payload));

            // Prepare CLOSE Frame
            buf.put((byte)(0x00 | FIN | OpCode.CLOSE.getCode()));
            putPayloadLength(buf,2);
            putMask(buf);
            buf.put(masked(new byte[]
            { 0x03, (byte)0xE8 }));

            // Write Data Frame
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);
            client.flush();

            // Read frames
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);

            // Validate close w/ Protocol Error
            WebSocketFrame frame = capture.getFrames().pop();
            Assert.assertThat("CLOSE.frame.opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("CLOSE.statusCode",close.getStatusCode(),is(StatusCode.PROTOCOL));
        }
        finally
        {
            client.disconnect();
        }
    }

    /**
     * Send a ping frame as separate segments, in an inefficient way.
     * 
     * @param payload
     *            the payload
     * @param segmentSize
     *            the segment size for each inefficient segment (flush between)
     */
    private void assertSegmentedPingFrame(byte[] payload, int segmentSize) throws Exception
    {
        Assert.assertThat("payload exists for segmented send",payload,notNullValue());
        Assert.assertThat("payload exists for segmented send",payload.length,greaterThan(0));

        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(payload.length + Generator.OVERHEAD);
            BufferUtil.clearToFill(buf);

            // Prepare PING Frame
            buf.put((byte)(0x00 | FIN | OpCode.PING.getCode()));
            putPayloadLength(buf,payload.length);
            putMask(buf);
            buf.put(masked(payload));

            // Prepare CLOSE Frame
            buf.put((byte)(0x00 | FIN | OpCode.CLOSE.getCode()));
            putPayloadLength(buf,2);
            putMask(buf);
            buf.put(masked(new byte[]
            { 0x03, (byte)0xE8 }));

            // Write Data Frame
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

            // Read frames
            IncomingFramesCapture capture = client.readFrames(2,TimeUnit.MILLISECONDS,500);

            // Validate echo'd frame
            WebSocketFrame frame = capture.getFrames().get(0);
            Assert.assertThat("frame should be PONG frame",frame.getOpCode(),is(OpCode.PONG));
            Assert.assertThat("PONG.payloadLength",frame.getPayloadLength(),is(payload.length));
            ByteBufferAssert.assertEquals("PONG.payload",payload,frame.getPayload());

            // Validate close
            frame = capture.getFrames().get(1);
            Assert.assertThat("CLOSE.frame.opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("CLOSE.statusCode",close.getStatusCode(),is(StatusCode.NORMAL));
        }
        finally
        {
            client.disconnect();
        }
    }

    /**
     * Ping without payload
     */
    @Test
    public void testCase2_1() throws Exception
    {
        byte payload[] = new byte[0];
        assertPingFrame(payload);
    }

    /**
     * 10 pings
     */
    @Test
    public void testCase2_10() throws Exception
    {
        // send 10 pings each with unique payload
        // send close
        // expect 10 pongs with OUR payload
        // expect close
    }

    /**
     * 10 pings, sent slowly
     */
    @Test
    public void testCase2_11() throws Exception
    {
        // send 10 pings (slowly) each with unique payload
        // send close
        // expect 10 pongs with OUR payload
        // expect close
    }

    /**
     * Ping with small text payload
     */
    @Test
    public void testCase2_2() throws Exception
    {
        byte payload[] = StringUtil.getUtf8Bytes("Hello world");
        assertPingFrame(payload);
    }

    /**
     * Ping with small binary (non-utf8) payload
     */
    @Test
    public void testCase2_3() throws Exception
    {
        byte payload[] = new byte[]
        { 0x00, (byte)0xFF, (byte)0xFE, (byte)0xFD, (byte)0xFC, (byte)0xFB, 0x00, (byte)0xFF };
        assertPingFrame(payload);
    }

    /**
     * Ping with 125 byte binary payload
     */
    @Test
    public void testCase2_4() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload,(byte)0xFE);
        assertPingFrame(payload);
    }

    /**
     * Ping with 126 byte binary payload
     */
    @Test
    public void testCase2_5() throws Exception
    {
        byte payload[] = new byte[126];
        Arrays.fill(payload,(byte)0xFE);
        assertProtocolError(payload);
    }

    /**
     * Ping with 125 byte binary payload (slow send)
     */
    @Test
    public void testCase2_6() throws Exception
    {
        byte payload[] = new byte[125];
        Arrays.fill(payload,(byte)0xFE);
        assertSegmentedPingFrame(payload,1);
    }

    /**
     * Unsolicited pong frame without payload
     */
    @Test
    public void testCase2_7() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte payload[] = new byte[0];

            ByteBuffer buf = ByteBuffer.allocate(256);
            BufferUtil.clearToFill(buf);

            // Prepare Unsolicited PONG Frame
            buf.put((byte)(0x00 | FIN | OpCode.PONG.getCode()));
            putPayloadLength(buf,payload.length);
            putMask(buf);
            // buf.put(masked(payload));

            // Prepare CLOSE Frame
            buf.put((byte)(0x00 | FIN | OpCode.CLOSE.getCode()));
            putPayloadLength(buf,2);
            putMask(buf);
            buf.put(masked(new byte[]
            { 0x03, (byte)0xE8 }));

            // Write Data Frame
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);
            client.flush();

            // Read frames
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);

            // Validate close
            WebSocketFrame frame = capture.getFrames().pop();
            Assert.assertThat("CLOSE.frame.opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("CLOSE.statusCode",close.getStatusCode(),is(StatusCode.NORMAL));
        }
        finally
        {
            client.disconnect();
        }

    }

    /**
     * Unsolicited pong frame with basic payload
     */
    @Test
    public void testCase2_8() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte payload[] = StringUtil.getUtf8Bytes("unsolicited");

            ByteBuffer buf = ByteBuffer.allocate(256);
            BufferUtil.clearToFill(buf);

            // Prepare Unsolicited PONG Frame
            buf.put((byte)(0x00 | FIN | OpCode.PONG.getCode()));
            putPayloadLength(buf,payload.length);
            putMask(buf);
            buf.put(masked(payload));

            // Prepare CLOSE Frame
            buf.put((byte)(0x00 | FIN | OpCode.CLOSE.getCode()));
            putPayloadLength(buf,2);
            putMask(buf);
            buf.put(masked(new byte[]
            { 0x03, (byte)0xE8 }));

            // Write Data Frame
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);
            client.flush();

            // Read frames
            IncomingFramesCapture capture = client.readFrames(1,TimeUnit.MILLISECONDS,500);

            // Validate close
            WebSocketFrame frame = capture.getFrames().pop();
            Assert.assertThat("CLOSE.frame.opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("CLOSE.statusCode",close.getStatusCode(),is(StatusCode.NORMAL));
        }
        finally
        {
            client.disconnect();
        }
    }

    /**
     * Unsolicited pong frame, then ping with basic payload
     */
    @Test
    public void testCase2_9() throws Exception
    {
        // send unsolicited pong with payload.
        // send OUR ping with payload
        // send close
        // expect pong with OUR payload
        // expect close

        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte pongPayload[] = StringUtil.getUtf8Bytes("unsolicited");

            ByteBuffer buf = ByteBuffer.allocate(512);
            BufferUtil.clearToFill(buf);

            // Prepare Unsolicited PONG Frame
            buf.put((byte)(0x00 | FIN | OpCode.PONG.getCode()));
            putPayloadLength(buf,pongPayload.length);
            putMask(buf);
            buf.put(masked(pongPayload));

            // Prepare our PING with payload
            byte pingPayload[] = StringUtil.getUtf8Bytes("ping me");
            buf.put((byte)(0x00 | FIN | OpCode.PING.getCode()));
            putPayloadLength(buf,pingPayload.length);
            putMask(buf);
            buf.put(masked(pingPayload));

            // Prepare CLOSE Frame
            buf.put((byte)(0x00 | FIN | OpCode.CLOSE.getCode()));
            putPayloadLength(buf,2);
            putMask(buf);
            buf.put(masked(new byte[]
            { 0x03, (byte)0xE8 }));

            // Write Data Frame
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);
            client.flush();

            // Read frames
            IncomingFramesCapture capture = client.readFrames(2,TimeUnit.MILLISECONDS,500);

            // Validate PONG
            WebSocketFrame frame = capture.getFrames().pop();
            Assert.assertThat("frame should be PONG frame",frame.getOpCode(),is(OpCode.PONG));
            Assert.assertThat("PONG.payloadLength",frame.getPayloadLength(),is(pingPayload.length));
            ByteBufferAssert.assertEquals("PONG.payload",pingPayload,frame.getPayload());

            // Validate close
            frame = capture.getFrames().pop();
            Assert.assertThat("CLOSE.frame.opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("CLOSE.statusCode",close.getStatusCode(),is(StatusCode.NORMAL));
        }
        finally
        {
            client.disconnect();
        }

    }

}
