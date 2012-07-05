package org.eclipse.jetty.websocket.server.ab;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.FrameGenerator;
import org.eclipse.jetty.websocket.protocol.OpCode;
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
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

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
                getConnection().write(message);
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
            
            // Read frame (hopefully text frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            CloseFrame closeFrame = (CloseFrame)frames.remove();
            Assert.assertThat("CloseFrame.status code",closeFrame.getStatusCode(),is(1002));
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

            // Read frame (hopefully text frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            CloseFrame closeFrame = (CloseFrame)frames.remove();
            Assert.assertThat("CloseFrame.status code",closeFrame.getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    
    
    @Test
    @Ignore ("not re-assembling the strings yet on server side echo")
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

            // Read frame (hopefully text frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            TextFrame textFrame = (TextFrame)frames.remove();
            Assert.assertThat("TextFrame.payload",textFrame.getPayloadUTF8(),is(fragment1 + fragment2));
        }
        finally
        {
            client.close();
        }
    }
}
