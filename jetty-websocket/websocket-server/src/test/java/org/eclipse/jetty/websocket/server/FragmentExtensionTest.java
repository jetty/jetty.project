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

public class FragmentExtensionTest
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
    public void testFragmentExtension() throws Exception
    {

        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.clearExtensions();
        client.addExtensions("fragment;maxLength=4;minFragments=7");
        client.setProtocols("onConnect");

        try
        {
            // Make sure the read times out if there are problems with the implementation
            client.setTimeout(TimeUnit.SECONDS,1);
            client.connect();
            client.sendStandardRequest();
            String resp = client.expectUpgradeResponse();

            Assert.assertThat("Response",resp,containsString("fragment"));

            String msg = "Sent as a long message that should be split";
            client.write(FrameBuilder.text(msg).asFrame());

            // TODO: use socket that captures frame counts to verify fragmentation

            Queue<WebSocketFrame> frames = client.readFrames(1,TimeUnit.MILLISECONDS,1000);
            WebSocketFrame frame = frames.remove();
            Assert.assertThat("TEXT.payload",frame.getPayloadAsUTF8(),is(msg));
        }
        finally
        {
            client.close();
        }
    }
}
