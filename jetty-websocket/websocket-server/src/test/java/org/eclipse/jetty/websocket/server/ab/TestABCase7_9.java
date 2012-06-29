package org.eclipse.jetty.websocket.server.ab;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.frames.BaseFrame;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.server.SimpleServletServer;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.server.WebSocketServlet;
import org.eclipse.jetty.websocket.server.WebSocketServletRFCTest.RFCServlet;
import org.eclipse.jetty.websocket.server.WebSocketServletRFCTest.RFCSocket;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(value = Parameterized.class)
public class TestABCase7_9
{
    private int invalidStatusCode;
    
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
    
    public TestABCase7_9(Integer invalidStatusCode )
    {
        this.invalidStatusCode = invalidStatusCode;
    }
    
    
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
        server = new SimpleServletServer(new RFCServlet());
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test the requirement of issuing
     */
    @Test
    @Ignore ("tossing a buffer overflow exception for some reason")
    public void testCase7_9_XInvalidCloseStatusCodes() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        try
        {
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();

            // Generate text frame
            client.write(new CloseFrame(invalidStatusCode)
            {
                @Override
                public void assertValidPayload(int statusCode, String reason)
                {

                }
                
            });

            // Read frame (hopefully text frame)
            Queue<BaseFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,500);
            CloseFrame closeFrame = (CloseFrame)frames.remove();
            Assert.assertThat("CloseFrame.status code", closeFrame.getStatusCode(),is(1002));
        }
        finally
        {
            client.close();
        }
    }

    
    
}
