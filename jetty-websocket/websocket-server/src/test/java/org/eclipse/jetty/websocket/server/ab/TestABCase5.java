package org.eclipse.jetty.websocket.server.ab;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.generator.FrameGenerator;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.ByteBufferAssert;
import org.eclipse.jetty.websocket.server.SimpleServletServer;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class TestABCase5
{
    @SuppressWarnings("serial")
    public static class RFCServlet extends WebSocketServlet
    {
        @Override
        public void registerWebSockets(WebSocketServerFactory factory)
        {
            factory.register(RFCSocket.class);
        }
    }

    public static class RFCSocket extends WebSocketAdapter
    {
        private static Logger LOG = Log.getLogger(RFCSocket.class);

        @Override
        public void onWebSocketText(String message)
        {
            LOG.debug("onWebSocketText({})",message);
            // Test the RFC 6455 close code 1011 that should close
            // trigger a WebSocket server terminated close.
            if (message.equals("CRASH"))
            {
                System.out.printf("Got OnTextMessage");
                throw new RuntimeException("Something bad happened");
            }

            // echo the message back.
            try
            {
                getConnection().write(null,new FutureCallback<Void>(),message);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
    }

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
    public void testCase5_1PingIn2Packets() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.PING.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            ByteBuffer buf2 = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf2);

            String fragment2 = "fragment2";

            buf2.put((byte)(0x80 | OpCode.PING.getCode()));
            b = 0x00; // no masking
            b |= fragment2.length() & 0x7F;
            buf2.put(b);
            buf2.put(fragment2.getBytes());
            BufferUtil.flipToFlush(buf2,0);

            client.writeRaw(buf2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame", frame.getOpCode(), is(OpCode.CLOSE) );

            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testCase5_1PingIn2PacketsWithBuilder() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            String fragment1 = "fragment1";
            ByteBuffer frame1 = FrameBuilder.ping().fin(false).payload(fragment1.getBytes()).asByteBuffer();
        
            client.writeRaw(frame1);

            String fragment2 = "fragment2";
            ByteBuffer frame2 = FrameBuilder.ping().payload(fragment2.getBytes()).asByteBuffer();
            client.writeRaw(frame2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame", frame.getOpCode(), is(OpCode.CLOSE));

            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testCase5_2PongIn2Packets() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.PONG.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            ByteBuffer buf2 = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf2);

            String fragment2 = "fragment2";

            buf2.put((byte)(0x80 | OpCode.CONTINUATION.getCode()));
            b = 0x00; // no masking
            b |= fragment2.length() & 0x7F;
            buf2.put(b);
            buf2.put(fragment2.getBytes());
            BufferUtil.flipToFlush(buf2,0);

            client.writeRaw(buf2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame", frame.getOpCode(), is(OpCode.CLOSE));

            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testCase5_2PongIn2PacketsWithBuilder() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            String fragment1 = "fragment1";

            ByteBuffer frame1 = FrameBuilder.pong().fin(false).payload(fragment1.getBytes()).asByteBuffer();
                        

            client.writeRaw(frame1);

            String fragment2 = "fragment2";

            ByteBuffer frame2 = FrameBuilder.continuation().fin(false).payload(fragment2.getBytes()).asByteBuffer();
          
            client.writeRaw(frame2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame", frame.getOpCode(), is(OpCode.CLOSE));

            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("not supported in implementation yet, requires server side message aggregation")
    public void testCase5_3TextIn2Packets() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.TEXT.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            ByteBuffer buf2 = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf2);

            String fragment2 = "fragment2";

            buf2.put((byte)(0x80 | OpCode.CONTINUATION.getCode()));
            b = 0x00; // no masking
            b |= fragment2.length() & 0x7F;
            buf2.put(b);
            buf2.put(fragment2.getBytes());
            BufferUtil.flipToFlush(buf2,0);

            client.writeRaw(buf2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be text frame",frame.getOpCode(), is(OpCode.TEXT));

            Assert.assertThat("TextFrame.payload",frame.getPayloadAsUTF8(),is(fragment1 + fragment2));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("not supported in implementation yet, requires server side message aggregation")
    public void testCase5_6TextPingRemainingText() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Send a text packet

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.TEXT.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            // Send a ping with payload

            ByteBuffer pingBuf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(pingBuf);

            String pingPayload = "ping payload";

            pingBuf.put((byte)(0x00 | OpCode.PING.getCode()));

            b = 0x00; // no masking
            b |= pingPayload.length() & 0x7F;
            pingBuf.put(b);
            pingBuf.put(pingPayload.getBytes());
            BufferUtil.flipToFlush(pingBuf,0);

            client.writeRaw(buf);

            // Send remaining text as continuation

            ByteBuffer buf2 = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf2);

            String fragment2 = "fragment2";

            buf2.put((byte)(0x80 | OpCode.CONTINUATION.getCode()));
            b = 0x00; // no masking
            b |= fragment2.length() & 0x7F;
            buf2.put(b);
            buf2.put(fragment2.getBytes());
            BufferUtil.flipToFlush(buf2,0);

            client.writeRaw(buf2);

            // Should be 2 frames, pong frame followed by combined echo'd text frame
            Queue<WebSocketFrame> frames = client.readFrames(2,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("first frame should be pong frame",frame.getOpCode(), is(OpCode.PING));

            ByteBuffer payload1 = ByteBuffer.allocate(pingPayload.length());
            payload1.flip();

            Assert.assertArrayEquals("payloads should be equal",BufferUtil.toArray(payload1),frame.getPayloadData());

            frame = frames.remove();

            Assert.assertThat("second frame should be text frame",frame.getOpCode(), is( OpCode.TEXT));

            Assert.assertThat("TextFrame.payload",frame.getPayloadAsUTF8(),is(fragment1 + fragment2));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("not supported in implementation yet, requires server side message aggregation")
    public void testCase5_6TextPingRemainingTextWithBuilder() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Send a text packet
            String textPayload1 = "fragment1";

            ByteBuffer frame1 = FrameBuilder.text().fin(false).payload(textPayload1.getBytes()).asByteBuffer();
            BufferUtil.flipToFlush(frame1,0);
            client.writeRaw(frame1);

            // Send a ping with payload

            String pingPayload = "ping payload";
            ByteBuffer frame2 = FrameBuilder.ping().payload(pingPayload.getBytes()).asByteBuffer();
            BufferUtil.flipToFlush(frame2,0);

            client.writeRaw(frame2);

            // Send remaining text as continuation
            String textPayload2 = "fragment2";

            ByteBuffer frame3 = FrameBuilder.continuation().payload(textPayload2.getBytes()).asByteBuffer();
            BufferUtil.flipToFlush(frame3,0);

            client.writeRaw(frame3);

            // Should be 2 frames, pong frame followed by combined echo'd text frame
            Queue<WebSocketFrame> frames = client.readFrames(2,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("first frame should be pong frame",frame.getOpCode(), is(OpCode.PONG));

            ByteBuffer payload1 = ByteBuffer.allocate(pingPayload.length());
            payload1.flip();

            Assert.assertArrayEquals("payloads should be equal",BufferUtil.toArray(payload1),frame.getPayloadData());

            frame = frames.remove();

            Assert.assertThat("second frame should be text frame",frame.getOpCode(), is(OpCode.TEXT));

            Assert.assertThat("TextFrame.payload",frame.getPayloadAsUTF8(),is(textPayload1 + textPayload2));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("AB tests have chop concepts currently unsupported by test...I think, also the string being returns is not Bad Continuation")
    public void testCase5_9BadContinuation() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Send a text packet

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment";

            // continutation w / FIN

            buf.put((byte)(0x80 | OpCode.CONTINUATION.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame",frame.getOpCode(), is(OpCode.CLOSE));

            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));

            Assert.assertThat("CloseFrame.reason",new CloseInfo(frame).getReason(),is("Bad Continuation")); // TODO put close reasons into public strings in impl
                                                                                                           // someplace

        }
        finally
        {
            client.close();
        }
    }
}
