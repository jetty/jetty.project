package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 * <p>
 * This test serves a different purpose than than the {@link WebSocketMessageRFC6455Test}, and {@link WebSocketParserRFC6455Test} tests.
 */
public class WebSocketServletRFCTest
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

    @WebSocket
    public static class RFCSocket
    {
        private static Logger LOG = Log.getLogger(RFCSocket.class);

        private WebSocketConnection conn;

        @OnWebSocketMessage
        public void onBinary(byte buf[], int offset, int len)
        {
            LOG.debug("onBinary(byte[{}],{},{})",buf.length,offset,len);

            // echo the message back.
            try
            {
                this.conn.write(null,new FutureCallback<Void>(),buf,offset,len);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }

        @OnWebSocketConnect
        public void onOpen(WebSocketConnection conn)
        {
            this.conn = conn;
        }

        @OnWebSocketMessage
        public void onText(String message)
        {
            LOG.debug("onText({})",message);
            // Test the RFC 6455 close code 1011 that should close
            // trigger a WebSocket server terminated close.
            if (message.equals("CRASH"))
            {
                throw new RuntimeException("Something bad happened");
            }

            // echo the message back.
            try
            {
                this.conn.write(null,new FutureCallback<Void>(),message);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }
    }

    private static Generator generator = new UnitGenerator();
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new RFCServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test that aggregation of binary frames into a single message occurs
     */
    @Test
    public void testBinaryAggregate() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate binary frames
            byte buf1[] = new byte[128];
            byte buf2[] = new byte[128];
            byte buf3[] = new byte[128];

            Arrays.fill(buf1,(byte)0xAA);
            Arrays.fill(buf2,(byte)0xBB);
            Arrays.fill(buf3,(byte)0xCC);

            WebSocketFrame bin;

            bin = WebSocketFrame.binary(buf1).setFin(false);

            client.write(bin); // write buf1 (fin=false)

            bin = new WebSocketFrame(OpCode.CONTINUATION).setPayload(buf2).setFin(false);

            client.write(bin); // write buf2 (fin=false)

            bin = new WebSocketFrame(OpCode.CONTINUATION).setPayload(buf3).setFin(true);

            client.write(bin); // write buf3 (fin=true)

            // Read frame echo'd back (hopefully a single binary frame)
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,1000);
            WebSocketFrame binmsg = frames.remove();
            int expectedSize = buf1.length + buf2.length + buf3.length;
            Assert.assertThat("BinaryFrame.payloadLength",binmsg.getPayloadLength(),is(expectedSize));

            int aaCount = 0;
            int bbCount = 0;
            int ccCount = 0;

            ByteBuffer echod = binmsg.getPayload();
            while (echod.remaining() >= 1)
            {
                byte b = echod.get();
                switch (b)
                {
                    case (byte)0xAA:
                        aaCount++;
                        break;
                    case (byte)0xBB:
                        bbCount++;
                        break;
                    case (byte)0xCC:
                        ccCount++;
                        break;
                    default:
                        Assert.fail(String.format("Encountered invalid byte 0x%02X",(byte)(0xFF & b)));
                }
            }
            Assert.assertThat("Echoed data count for 0xAA",aaCount,is(buf1.length));
            Assert.assertThat("Echoed data count for 0xBB",bbCount,is(buf2.length));
            Assert.assertThat("Echoed data count for 0xCC",ccCount,is(buf3.length));
        }
        finally
        {
            client.close();
        }
    }

    @Test(expected = NotUtf8Exception.class)
    public void testDetectBadUTF8()
    {
        byte buf[] = new byte[]
        { (byte)0xC2, (byte)0xC3 };

        Utf8StringBuilder utf = new Utf8StringBuilder();
        utf.append(buf,0,buf.length);
    }

    /**
     * Test the requirement of issuing
     */
    @Test
    public void testEcho() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            client.write(WebSocketFrame.text(msg));

            // Read frame (hopefully text frame)
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame tf = frames.remove();
            Assert.assertThat("Text Frame.status code",tf.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    @Ignore("Idle Timeouts not working (yet)")
    public void testIdle() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("onConnect");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            client.write(WebSocketFrame.text("Hello"));

            // now wait for the server to time out
            // should be 2 frames, the TextFrame echo, and then the Close on disconnect
            Queue<WebSocketFrame> frames = client.readFrames(2,TimeUnit.SECONDS,5);
            Assert.assertThat("frames[0].opcode",frames.remove().getOpCode(),is(OpCode.TEXT));
            Assert.assertThat("frames[1].opcode",frames.remove().getOpCode(),is(OpCode.CLOSE));
        }
        finally
        {
            client.close();
        }
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * WebSocket POJO.
     */
    @Test
    public void testInternalError() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            client.write(WebSocketFrame.text("CRASH"));

            // Read frame (hopefully close frame)
            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            WebSocketFrame cf = frames.remove();
            CloseInfo close = new CloseInfo(cf);
            Assert.assertThat("Close Frame.status code",close.getStatusCode(),is(StatusCode.SERVER_ERROR));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testMaxBinarySize() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("other");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Choose a size for a single frame larger than the
            // server side policy
            int dataSize = 1024 * 100;
            byte buf[] = new byte[dataSize];
            Arrays.fill(buf,(byte)0x44);

            WebSocketFrame bin = WebSocketFrame.binary(buf).setFin(true);
            ByteBuffer bb = generator.generate(bin);
            BufferUtil.flipToFlush(bb,0);
            client.writeRaw(bb);

            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.MESSAGE_TOO_LARGE));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testMaxTextSize() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("other");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Choose a size for a single frame larger than the
            // server side policy
            int dataSize = 1024 * 100;
            byte buf[] = new byte[dataSize];
            Arrays.fill(buf,(byte)'z');

            WebSocketFrame text = WebSocketFrame.text().setPayload(buf).setFin(true);
            ByteBuffer bb = generator.generate(text);
            BufferUtil.flipToFlush(bb,0);
            client.writeRaw(bb);

            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.MESSAGE_TOO_LARGE));
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testTextNotUTF8() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setProtocols("other");
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            byte buf[] = new byte[]
            { (byte)0xC2, (byte)0xC3 };

            WebSocketFrame txt = WebSocketFrame.text().setPayload(buf);
            ByteBuffer bb = generator.generate(txt);
            BufferUtil.flipToFlush(bb,0);
            client.writeRaw(bb);

            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.SECONDS,1);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("frames[0].opcode",frame.getOpCode(),is(OpCode.CLOSE));
            CloseInfo close = new CloseInfo(frame);
            Assert.assertThat("Close Status Code",close.getStatusCode(),is(StatusCode.BAD_PAYLOAD));
        }
        finally
        {
            client.close();
        }
    }

}
