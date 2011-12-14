package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.websocket.helper.CaptureSocket;
import org.eclipse.jetty.websocket.helper.SafariD00;
import org.eclipse.jetty.websocket.helper.WebSocketCaptureServlet;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class SafariWebsocketDraft0Test
{
    private Server server;
    private WebSocketCaptureServlet servlet;
    private URI serverUri;

    @BeforeClass
    public static void initLogging()
    {
        // Configure Logging
        System.setProperty("org.eclipse.jetty.util.log.class",StdErrLog.class.getName());
        System.setProperty("org.eclipse.jetty.LEVEL","DEBUG");
    }

    @Before
    public void startServer() throws Exception
    {
        // Configure Server
        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        servlet = new WebSocketCaptureServlet();
        context.addServlet(new ServletHolder(servlet),"/");

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
    
    @Test
    @Ignore
    public void testSendTextMessages() throws Exception
    {
        SafariD00 safari = new SafariD00(serverUri);

        try
        {
            safari.connect();
            safari.issueHandshake();

            // Send 5 short messages, using technique seen in Safari.
            safari.sendMessage("aa-0"); // single msg
            safari.sendMessage("aa-1", "aa-2", "aa-3", "aa-4");

            // Servlet should show only 1 connection.
            Assert.assertThat("Servlet.captureSockets.size",servlet.captures.size(),is(1));

            CaptureSocket socket = servlet.captures.get(0);
            Assert.assertThat("CaptureSocket",socket,notNullValue());
            Assert.assertThat("CaptureSocket.isConnected", socket.isConnected(), is(true));

            // Give servlet 500 millisecond to process messages
            threadSleep(1,TimeUnit.SECONDS);
            // Should have captured 5 messages.
            Assert.assertThat("CaptureSocket.messages.size",socket.messages.size(),is(5));
        }
        finally
        {
            System.out.println("Closing client socket");
            safari.disconnect();
        }
    }
    
    public static void threadSleep(int dur, TimeUnit unit) throws InterruptedException
    {
        long ms = TimeUnit.MILLISECONDS.convert(dur,unit);
        Thread.sleep(ms);
    }
    
    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
}
