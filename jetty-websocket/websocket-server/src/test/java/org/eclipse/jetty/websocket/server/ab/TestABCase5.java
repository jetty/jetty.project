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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Generator;
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

    private static Generator laxGenerator;

    @BeforeClass
    public static void initGenerators()
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        ByteBufferPool bufferPool = new StandardByteBufferPool();
        laxGenerator = new Generator(policy,bufferPool,false);
    }

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

            ByteBuffer buf = ByteBuffer.allocate(Generator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.PING.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            ByteBuffer buf2 = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            Assert.assertThat("frame should be close frame",frame.getOpCode(),is(OpCode.CLOSE));

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
            WebSocketFrame frame1 = WebSocketFrame.ping().setFin(false).setPayload(fragment1);
            ByteBuffer buf1 = laxGenerator.generate(frame1);
            client.writeRaw(buf1);

            String fragment2 = "fragment2";
            WebSocketFrame frame2 = WebSocketFrame.ping().setPayload(fragment2);
            ByteBuffer buf2 = laxGenerator.generate(frame2);
            client.writeRaw(buf2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame",frame.getOpCode(),is(OpCode.CLOSE));

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

            ByteBuffer buf = ByteBuffer.allocate(Generator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.PONG.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            ByteBuffer buf2 = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            Assert.assertThat("frame should be close frame",frame.getOpCode(),is(OpCode.CLOSE));

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
            WebSocketFrame frame1 = WebSocketFrame.pong().setFin(false).setPayload(fragment1);
            ByteBuffer buf1 = laxGenerator.generate(frame1);
            client.writeRaw(buf1);

            String fragment2 = "fragment2";
            WebSocketFrame frame2 = new WebSocketFrame(OpCode.CONTINUATION).setFin(false).setPayload(fragment2);
            ByteBuffer buf2 = laxGenerator.generate(frame2);
            client.writeRaw(buf2);

            // Read frame
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("frame should be close frame",frame.getOpCode(),is(OpCode.CLOSE));
            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testCase5_3TextIn2Packets() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(Generator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            String fragment1 = "fragment1";

            buf.put((byte)(0x00 | OpCode.TEXT.getCode()));

            byte b = 0x00; // no masking
            b |= fragment1.length() & 0x7F;
            buf.put(b);
            buf.put(fragment1.getBytes());
            BufferUtil.flipToFlush(buf,0);

            client.writeRaw(buf);

            ByteBuffer buf2 = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            Assert.assertThat("frame should be text frame",frame.getOpCode(),is(OpCode.TEXT));

            Assert.assertThat("TextFrame.payload",frame.getPayloadAsUTF8(),is(fragment1 + fragment2));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testCase5_6TextPingRemainingText() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Send a text packet

            ByteBuffer buf = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            ByteBuffer pingBuf = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            ByteBuffer buf2 = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            Assert.assertThat("first frame should be pong frame",frame.getOpCode(),is(OpCode.PING));

            ByteBuffer payload1 = ByteBuffer.allocate(pingPayload.length());
            payload1.flip();

            ByteBufferAssert.assertEquals("payloads should be equal",payload1,frame.getPayload());
            frame = frames.remove();

            Assert.assertThat("second frame should be text frame",frame.getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("TextFrame.payload",frame.getPayloadAsUTF8(),is(fragment1 + fragment2));
        }
        finally
        {
            client.close();
        }
    }

    @Test
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
            WebSocketFrame frame1 = WebSocketFrame.text().setFin(false).setPayload(textPayload1);
            ByteBuffer buf1 = laxGenerator.generate(frame1);
            client.writeRaw(buf1);

            // Send a ping with payload
            String pingPayload = "ping payload";
            WebSocketFrame frame2 = WebSocketFrame.ping().setPayload(pingPayload);
            ByteBuffer buf2 = laxGenerator.generate(frame2);
            client.writeRaw(buf2);

            // Send remaining text as continuation
            String textPayload2 = "fragment2";
            WebSocketFrame frame3 = new WebSocketFrame(OpCode.CONTINUATION).setPayload(textPayload2);
            ByteBuffer buf3 = laxGenerator.generate(frame3);
            client.writeRaw(buf3);

            // Should be 2 frames, pong frame followed by combined echo'd text frame
            Queue<WebSocketFrame> frames = client.readFrames(2,TimeUnit.MILLISECONDS,500);
            WebSocketFrame frame = frames.remove();

            Assert.assertThat("first frame should be pong frame",frame.getOpCode(),is(OpCode.PONG));

            ByteBuffer payload1 = ByteBuffer.allocate(pingPayload.length());
            payload1.flip();

            ByteBufferAssert.assertEquals("Payload",payload1,frame.getPayload());

            frame = frames.remove();

            Assert.assertThat("second frame should be text frame",frame.getOpCode(),is(OpCode.TEXT));

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

            ByteBuffer buf = ByteBuffer.allocate(Generator.OVERHEAD + 2);
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

            Assert.assertThat("frame should be close frame",frame.getOpCode(),is(OpCode.CLOSE));

            Assert.assertThat("CloseFrame.status code",new CloseInfo(frame).getStatusCode(),is(1002));

            Assert.assertThat("CloseFrame.reason",new CloseInfo(frame).getReason(),is("Bad Continuation")); // TODO put close reasons into public strings in
                                                                                                            // impl
                                                                                                            // someplace

        }
        finally
        {
            client.close();
        }
    }
}
