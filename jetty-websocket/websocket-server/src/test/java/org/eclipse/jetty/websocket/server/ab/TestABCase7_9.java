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
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.FrameBuilder;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(value = Parameterized.class)
public class TestABCase7_9
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

    @Parameters
    public static Collection<Integer[]> data()
    {
        List<Integer[]> data = new ArrayList<>();
        // @formatter:off
        data.add(new Integer[] { new Integer(0) });
        data.add(new Integer[] { new Integer(999) });
        data.add(new Integer[] { new Integer(1004) });
        data.add(new Integer[] { new Integer(1005) });
        data.add(new Integer[] { new Integer(1006) });
        data.add(new Integer[] { new Integer(1012) });
        data.add(new Integer[] { new Integer(1013) });
        data.add(new Integer[] { new Integer(1014) });
        data.add(new Integer[] { new Integer(1015) });
        data.add(new Integer[] { new Integer(1016) });
        data.add(new Integer[] { new Integer(1100) });
        data.add(new Integer[] { new Integer(2000) });
        data.add(new Integer[] { new Integer(2999) });

        // @formatter:on
        return data;
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

    private int invalidStatusCode;

    public TestABCase7_9(Integer invalidStatusCode)
    {
        this.invalidStatusCode = invalidStatusCode;
    }

    private void remask(ByteBuffer buf, int position, byte[] mask)
    {
        int end = buf.position();
        int off;
        for (int i = position; i < end; i++)
        {
            off = i - position;
            // Mask each byte by its absolute position in the bytebuffer
            buf.put(i,(byte)(buf.get(i) ^ mask[off % 4]));
        }
    }

    /**
     * Test the requirement of issuing
     */
    @Test
    public void testCase7_9_XInvalidCloseStatusCodes() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            // Create Close Frame manually, as we are testing the server's behavior of a bad client.
            buf.put((byte)(0x80 | OpCode.CLOSE.getCode()));
            buf.put((byte)(0x80 | 2));
            byte mask[] = new byte[]
            { 0x44, 0x44, 0x44, 0x44 };
            buf.put(mask);
            int position = buf.position();
            buf.putChar((char)this.invalidStatusCode);
            remask(buf,position,mask);
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

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

    /**
     * Test the requirement of issuing
     */
    @Test
    public void testCase7_9_XInvalidCloseStatusCodesWithBuilder() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            ByteBuffer frame = FrameBuilder.close().mask(new byte[]
            { 0x44, 0x44, 0x44, 0x44 }).asByteBuffer();

            ByteBuffer buf = ByteBuffer.allocate(FrameGenerator.OVERHEAD + 2);
            BufferUtil.clearToFill(buf);

            // Create Close Frame manually, as we are testing the server's behavior of a bad client.
            buf.put((byte)(0x80 | OpCode.CLOSE.getCode()));
            buf.put((byte)(0x80 | 2));
            byte mask[] = new byte[]
            { 0x44, 0x44, 0x44, 0x44 };
            buf.put(mask);
            int position = buf.position();
            buf.putChar((char)this.invalidStatusCode);
            remask(buf,position,mask);
            BufferUtil.flipToFlush(buf,0);
            client.writeRaw(buf);

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

}
