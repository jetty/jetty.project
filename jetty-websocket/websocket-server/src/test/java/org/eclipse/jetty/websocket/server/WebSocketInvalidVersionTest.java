package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import org.eclipse.jetty.websocket.server.blockhead.BlockheadClient;
import org.eclipse.jetty.websocket.server.examples.MyEchoServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketInvalidVersionTest
{
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

    /**
     * Test the requirement of responding with an http 400 when using a Sec-WebSocket-Version that is unsupported.
     */
    @Test
    public void testRequestVersion29() throws Exception
    {
        BlockheadClient client = new BlockheadClient(server.getServerUri());
        client.setVersion(29); // intentionally bad version
        try
        {
            client.connect();
            client.sendStandardRequest();
            String respHeader = client.readResponseHeader();
            Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 400 Unsupported websocket version specification"));
            Assert.assertThat("Response Header Versions",respHeader,containsString("Sec-WebSocket-Version: 13, 0\r\n"));
        }
        finally
        {
            client.close();
        }
    }
}
