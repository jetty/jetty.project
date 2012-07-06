package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.eclipse.jetty.websocket.server.WebSocketServletRFCTest.RFCServlet;
import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class IdentityExtensionTest
{
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

    @Test
    public void testIdentityExtension() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.clearExtensions();
        client.addExtensions("identity;param=0");
        client.addExtensions("identity;param=1, identity ; param = '2' ; other = ' some = value '");
        client.setProtocols("onConnect");

        try
        {
            // Make sure the read times out if there are problems with the implementation
            client.setTimeout(TimeUnit.SECONDS,1);
            client.connect();
            client.sendStandardRequest();
            String resp = client.expectUpgradeResponse();

            Assert.assertThat("Response",resp,containsString("identity"));

            client.write(FrameBuilder.text("Hello").asFrame());

            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,1000);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("TEXT.payload",frame.getPayloadAsUTF8(),is("Hello"));
        }
        finally
        {
            client.close();
        }
    }
}
