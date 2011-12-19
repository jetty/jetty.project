package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.helper.MessageSender;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on
 * {@link WebSocketServlet}
 * <p>
 * This test serves a different purpose than than the {@link WebSocketGeneratorRFC6455Test},
 * {@link WebSocketMessageRFC6455Test}, and {@link WebSocketParserRFC6455Test} tests.
 */
public class WebSocketServletRFCTest
{
    private static class RFCSocket implements WebSocket, WebSocket.OnTextMessage
    {
        private Connection conn;

        public void onOpen(Connection connection)
        {
            this.conn = connection;
        }

        public void onClose(int closeCode, String message)
        {
            this.conn = null;
        }

        public void onMessage(String data)
        {
            // Test the RFC 6455 close code 1011 that should close
            // trigger a WebSocket server terminated close.
            if (data.equals("CRASH"))
            {
                throw new RuntimeException("Something bad happened");
            }

            // echo the message back.
            try
            {
                conn.sendMessage(data);
            }
            catch (IOException e)
            {
                e.printStackTrace(System.err);
            }
        }

    }

    @SuppressWarnings("serial")
    private static class RFCServlet extends WebSocketServlet
    {
        public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
        {
            return new RFCSocket();
        }
    }

    private static Server server;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        // Configure Server
        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        context.addServlet(new ServletHolder(new RFCServlet()),"/");

        // Start Server
        server.start();

        Connector conn = server.getConnectors()[0];
        String host = conn.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = conn.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
        System.out.printf("Server URI: %s%n",serverUri);
    }

    @AfterClass
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    /**
     * Test the requirement of responding with an http 400 when using a Sec-WebSocket-Version that is unsupported.
     */
    @Test
    public void testResponseOnInvalidVersion() throws Exception
    {
        // Using straight HttpUrlConnection to accomplish this as jetty's WebSocketClient
        // doesn't allow the use of invalid versions. (obviously)

        URL url = new URL("http://" + serverUri.getHost() + ":" + serverUri.getPort() + "/");
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestProperty("Upgrade","WebSocket");
        conn.setRequestProperty("Connection","Upgrade");
        conn.setRequestProperty("Sec-WebSocket-Version","29");
        conn.connect();

        Assert.assertEquals("Response Code",400,conn.getResponseCode());
        Assert.assertThat("Response Message",conn.getResponseMessage(),containsString("Unsupported websocket version specification"));
        Assert.assertThat("Response Header Versions",conn.getHeaderField("Sec-WebSocket-Version"),is("13, 8, 6, 0"));

        conn.disconnect();
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal
     * server error) being produced by the extended WebSocketServlet.
     */
    @Test
    public void testResponseOnInternalError() throws Exception
    {
        WebSocketClientFactory clientFactory = new WebSocketClientFactory();
        clientFactory.start();

        WebSocketClient wsc = clientFactory.newWebSocketClient();
        MessageSender sender = new MessageSender();
        wsc.open(serverUri,sender);

        try
        {
            sender.awaitConnect();

            sender.sendMessage("CRASH");

            // Give servlet 500 millisecond to process messages
            TimeUnit.MILLISECONDS.sleep(500);
            
            Assert.assertThat("WebSocket should be closed", sender.isConnected(), is(false));
            Assert.assertThat("WebSocket close clode", sender.getCloseCode(), is(1011));
        }
        finally
        {
            sender.close();
        }
    }
}
