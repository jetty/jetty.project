package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.lessThan;

public class SlowClientWithPipelinedRequestTest
{
    private final AtomicInteger handles = new AtomicInteger();
    private Server server;
    private SelectChannelConnector connector;

    public void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new SelectChannelConnector()
        {
            @Override
            protected AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint)
            {
                return new AsyncHttpConnection(this, endpoint, getServer())
                {
                    @Override
                    public Connection handle() throws IOException
                    {
                        handles.incrementAndGet();
                        return super.handle();
                    }
                };
            }
        };
        server.addConnector(connector);
        connector.setPort(0);
        server.setHandler(handler);
        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    @Test
    public void testSlowClientWithPipelinedRequest() throws Exception
    {
        final int contentLength = 512 * 1024;
        startServer(new AbstractHandler()
        {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                System.err.println("target = " + target);
                if ("/content".equals(target))
                {
                    // We simulate what the DefaultServlet does, bypassing the blocking
                    // write mechanism otherwise the test does not reproduce the bug

                    OutputStream outputStream = response.getOutputStream();
                    AbstractHttpConnection.Output output = (AbstractHttpConnection.Output)outputStream;
                    // Since the test is via localhost, we need a really big buffer to stall the write
                    byte[] bytes = new byte[contentLength];
                    Arrays.fill(bytes, (byte)'9');
                    Buffer buffer = new ByteArrayBuffer(bytes);
                    // Do a non blocking write
                    output.sendContent(buffer);
                }
            }
        });

        Socket client = new Socket("localhost", connector.getLocalPort());
        OutputStream output = client.getOutputStream();
        output.write(("" +
                "GET /content HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n" +
                "").getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();

        int read = input.read();
        Assert.assertTrue(read >= 0);
        // As soon as we can read the response, send a pipelined request
        // so it is a different read for the server and it will trigger NIO
        output.write(("" +
                "GET /pipelined HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n" +
                "").getBytes("UTF-8"));
        output.flush();

        // Simulate a slow reader
        Thread.sleep(1000);
        Assert.assertThat(handles.get(), lessThan(10));

        // We are sure we are not spinning, read the content
        StringBuilder lines = new StringBuilder().append((char)read);
        int crlfs = 0;
        while (true)
        {
            read = input.read();
            lines.append((char)read);
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
        Assert.assertTrue(lines.toString().contains(" 200 "));
        // Read the body
        for (int i = 0; i < contentLength; ++i)
            input.read();

        // Read the pipelined response
        lines.setLength(0);
        crlfs = 0;
        while (true)
        {
            read = input.read();
            lines.append((char)read);
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
        Assert.assertTrue(lines.toString().contains(" 200 "));

        client.close();
    }
}
