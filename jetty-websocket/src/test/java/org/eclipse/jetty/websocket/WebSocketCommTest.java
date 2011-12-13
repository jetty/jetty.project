package org.eclipse.jetty.websocket;

import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * WebSocketCommTest - to test reported undelivered messages in bug <a
 * href="https://jira.codehaus.org/browse/JETTY-1463">JETTY-1463</a>
 */
public class WebSocketCommTest
{
    @SuppressWarnings("serial")
    private static class WebSocketCaptureServlet extends WebSocketServlet
    {
        public List<CaptureSocket> captures = new ArrayList<CaptureSocket>();;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.sendError(404);
        }

        public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
        {
            CaptureSocket capture = new CaptureSocket();
            captures.add(capture);
            return capture;
        }
    }

    private static class CaptureSocket implements WebSocket, WebSocket.OnTextMessage
    {
        private Connection conn;
        public List<String> messages;

        public CaptureSocket()
        {
            messages = new ArrayList<String>();
        }

        public boolean isConnected()
        {
            if (conn == null)
            {
                return false;
            }
            return conn.isOpen();
        }

        public void onMessage(String data)
        {
            System.out.printf("Received Message \"%s\" [size %d]%n", data, data.length());
            messages.add(data);
        }

        public void onOpen(Connection connection)
        {
            this.conn = connection;
        }

        public void onClose(int closeCode, String message)
        {
            this.conn = null;
        }
    }

    public static class MessageSender implements WebSocket
    {
        private Connection conn;
        private CountDownLatch connectLatch = new CountDownLatch(1);

        public void onOpen(Connection connection)
        {
            this.conn = connection;
            connectLatch.countDown();
        }

        public void onClose(int closeCode, String message)
        {
            this.conn = null;
        }

        public boolean isConnected()
        {
            if (this.conn == null)
            {
                return false;
            }
            return this.conn.isOpen();
        }

        public void sendMessage(String format, Object... args) throws IOException
        {
            this.conn.sendMessage(String.format(format,args));
        }

        public void awaitConnect() throws InterruptedException
        {
            connectLatch.await(1,TimeUnit.SECONDS);
        }

        public void close()
        {
            if (this.conn == null)
            {
                return;
            }
            this.conn.close();
        }
    }

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
    public void testSendTextMessages() throws Exception
    {
        WebSocketClientFactory clientFactory = new WebSocketClientFactory();
        clientFactory.start();

        WebSocketClient wsc = clientFactory.newWebSocketClient();
        MessageSender sender = new MessageSender();
        wsc.open(serverUri,sender);

        try
        {
            sender.awaitConnect();

            // Send 5 short messages
            for (int i = 0; i < 5; i++)
            {
                System.out.printf("Sending msg-%d%n",i);
                sender.sendMessage("msg-%d",i);
            }

            // Servlet should show only 1 connection.
            Assert.assertThat("Servlet.captureSockets.size",servlet.captures.size(),is(1));

            CaptureSocket socket = servlet.captures.get(0);
            Assert.assertThat("CaptureSocket",socket,notNullValue());
            Assert.assertThat("CaptureSocket.isConnected", socket.isConnected(), is(true));

            // Give servlet 500 millisecond to process messages
            threadSleep(500,TimeUnit.MILLISECONDS);
            // Should have captured 5 messages.
            Assert.assertThat("CaptureSocket.messages.size",socket.messages.size(),is(5));
        }
        finally
        {
            System.out.println("Closing client socket");
            sender.close();
        }
    }

    public static void threadSleep(int dur, TimeUnit unit) throws InterruptedException
    {
        long ms = TimeUnit.MILLISECONDS.convert(dur,unit);
        Thread.sleep(ms);
    }
}
