package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
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

            BinaryFrame bin;

            bin = new BinaryFrame();
            bin.setPayload(buf1);
            bin.setFin(false);

            client.write(bin); // write buf1 (fin=false)

            bin = new BinaryFrame();
            bin.setPayload(buf2);
            bin.setContinuation(true);
            bin.setFin(false);

            client.write(bin); // write buf2 (fin=false)

            bin = new BinaryFrame();
            bin.setPayload(buf3);
            bin.setContinuation(true);
            bin.setFin(true);

            client.write(bin); // write buf3 (fin=true)

            // Read frame echo'd back (hopefully a single binary frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            BinaryFrame binmsg = (BinaryFrame)frames.remove();
            Assert.assertThat("BinaryFrame.payloadLength",binmsg.getPayloadLength(),is(128 * 3));
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
    public void testEcho() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            TextFrame frame = new TextFrame("Hello World");
            frame.setFin(true);
            client.write(frame);

            // Read frame (hopefully text frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            TextFrame tf = (TextFrame)frames.remove();
            Assert.assertThat("Text Frame.status code",tf.getPayloadUTF8(),is("Hello World"));
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
            TextFrame frame = new TextFrame("CRASH");
            frame.setFin(true);
            client.write(frame);

            // Read frame (hopefully close frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            CloseFrame cf = (CloseFrame)frames.remove();
            Assert.assertThat("Close Frame.status code",cf.getStatusCode(),is(StatusCode.SERVER_ERROR));
        }
        finally
        {
            client.close();
        }
    }

}
