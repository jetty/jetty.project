package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.WebSocketGeneratorRFC6455Test;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.parser.Parser;
import org.eclipse.jetty.websocket.server.helper.FrameParseCapture;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test various <a href="http://tools.ietf.org/html/rfc6455">RFC 6455</a> specified requirements placed on {@link WebSocketServlet}
 * <p>
 * This test serves a different purpose than than the {@link WebSocketGeneratorRFC6455Test}, {@link WebSocketMessageRFC6455Test}, and
 * {@link WebSocketParserRFC6455Test} tests.
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

    public static class RFCSocket extends WebSocketAdapter
    {
        @Override
        public void onWebSocketText(String message)
        {
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

    private static Server server;
    private static SelectChannelConnector connector;
    private static URI serverUri;

    @BeforeClass
    public static void startServer() throws Exception
    {
        // Configure Server
        server = new Server();
        connector = new SelectChannelConnector();
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        context.addServlet(new ServletHolder(new RFCServlet()),"/*");

        // Start Server
        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
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

    private void read(InputStream in, ByteBuffer buf) throws IOException
    {
        while ((in.available() > 0) && (buf.remaining() > 0))
        {
            buf.put((byte)in.read());
        }
    }

    private String readResponseHeader(InputStream in) throws IOException
    {
        InputStreamReader isr = new InputStreamReader(in);
        BufferedReader reader = new BufferedReader(isr);
        StringBuilder header = new StringBuilder();
        // Read the response header
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertThat(line,startsWith("HTTP/1.1 "));
        header.append(line).append("\r\n");
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
            {
                break;
            }
            header.append(line).append("\r\n");
        }
        return header.toString();
    }

    /**
     * Test the requirement of responding with server terminated close code 1011 when there is an unhandled (internal server error) being produced by the
     * extended WebSocketServlet.
     */
    @Test
    public void testResponseOnInternalError() throws Exception
    {
        Socket socket = new Socket();
        SocketAddress endpoint = new InetSocketAddress(serverUri.getHost(),serverUri.getPort());
        socket.connect(endpoint);

        // acting as client
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        ByteBufferPool bufferPool = new StandardByteBufferPool(policy.getBufferSize());
        Generator generator = new Generator(policy);
        Parser parser = new Parser(policy);
        FrameParseCapture capture = new FrameParseCapture();
        parser.addListener(capture);

        StringBuilder req = new StringBuilder();
        req.append("GET / HTTP/1.1\r\n");
        req.append(String.format("Host: %s:%d\r\n",serverUri.getHost(),serverUri.getPort()));
        req.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
        req.append("Upgrade: WebSocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n"); // RFC 6455
        req.append("\r\n");

        OutputStream out = null;
        InputStream in = null;
        try
        {
            out = socket.getOutputStream();
            in = socket.getInputStream();

            // Write request
            out.write(req.toString().getBytes());
            out.flush();

            // Read response header
            String respHeader = readResponseHeader(in);
            // System.out.println("RESPONSE: " + respHeader);

            Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 101 Switching Protocols"));
            Assert.assertThat("Response Header Upgrade",respHeader,containsString("Upgrade: WebSocket\r\n"));
            Assert.assertThat("Response Header Connection",respHeader,containsString("Connection: Upgrade\r\n"));

            // Generate text frame
            TextFrame txt = new TextFrame("CRASH");
            ByteBuffer txtbuf = bufferPool.acquire(policy.getBufferSize(),false);
            try
            {
                BufferUtil.flipToFill(txtbuf);
                generator.generate(txtbuf,txt);
                BufferUtil.flipToFlush(txtbuf,0);

                // Write Text Frame
                BufferUtil.writeTo(txtbuf,out);
            }
            finally
            {
                bufferPool.release(txtbuf);
            }

            // Read frame (hopefully close frame)
            ByteBuffer rbuf = bufferPool.acquire(policy.getBufferSize(),false);
            try
            {
                BufferUtil.flipToFill(rbuf);
                read(in,rbuf);

                // Parse Frame
                BufferUtil.flipToFlush(rbuf,0);
                parser.parse(rbuf);
            }
            finally
            {
                bufferPool.release(rbuf);
            }

            // Validate responses
            capture.assertNoErrors();
            capture.assertHasFrame(CloseFrame.class,1);

            CloseFrame cf = (CloseFrame)capture.getFrames().get(0);
            Assert.assertThat("Close Frame.status code",cf.getStatusCode(),is((int)StatusCode.SERVER_ERROR));
        }
        finally
        {
            IO.close(in);
            IO.close(out);
            socket.close();
        }
    }

    /**
     * Test the requirement of responding with an http 400 when using a Sec-WebSocket-Version that is unsupported.
     */
    @Test
    public void testResponseOnInvalidVersion() throws Exception
    {
        Socket socket = new Socket();
        SocketAddress endpoint = new InetSocketAddress(serverUri.getHost(),serverUri.getPort());
        socket.connect(endpoint);

        StringBuilder req = new StringBuilder();
        req.append("GET / HTTP/1.1\r\n");
        req.append(String.format("Host: %s:%d\r\n",serverUri.getHost(),serverUri.getPort()));
        req.append("Upgrade: WebSocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Version: 29\r\n"); // bad version
        req.append("\r\n");

        OutputStream out = null;
        InputStream in = null;
        try
        {
            out = socket.getOutputStream();
            in = socket.getInputStream();

            // Write request
            out.write(req.toString().getBytes());
            out.flush();

            // Read response
            String respHeader = readResponseHeader(in);
            // System.out.println("RESPONSE: " + respHeader);

            Assert.assertThat("Response Code",respHeader,startsWith("HTTP/1.1 400 Unsupported websocket version specification"));
            Assert.assertThat("Response Header Versions",respHeader,containsString("Sec-WebSocket-Version: 13, 0\r\n"));
        }
        finally
        {
            IO.close(in);
            IO.close(out);
            socket.close();
        }
    }
}
