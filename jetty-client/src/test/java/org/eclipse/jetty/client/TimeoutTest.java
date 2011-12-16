package org.eclipse.jetty.client;

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class TimeoutTest
{
    private static final Logger logger = Log.getLogger(TimeoutTest.class);
    private final AtomicInteger httpParses = new AtomicInteger();
    private ExecutorService threadPool;
    private Server server;
    private int serverPort;
    
    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
        server = new Server();

        SelectChannelConnector connector = new SelectChannelConnector()
        {
            @Override
            protected AsyncConnection newConnection(SocketChannel channel,final AsyncEndPoint endPoint)
            {
                return new org.eclipse.jetty.server.AsyncHttpConnection(this, endPoint, getServer())
                {
                    @Override
                    protected HttpParser newHttpParser(Buffers requestBuffers, EndPoint endPoint, HttpParser.EventHandler requestHandler)
                    {
                        return new HttpParser(requestBuffers, endPoint, requestHandler)
                        {
                            @Override
                            public int parseNext() throws IOException
                            {
                                System.out.print(".");
                                httpParses.incrementAndGet();
                                return super.parseNext();
                            }
                        };
                    }
                };
            }
        };
        connector.setMaxIdleTime(2000);

//        connector.setPort(5870);
        connector.setPort(0);

        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
            {
                request.setHandled(true);
                String contentLength = request.getHeader("Content-Length");
                if (contentLength != null)
                {
                    int length = Integer.parseInt(contentLength);
                    ServletInputStream input = request.getInputStream();
                    for (int i = 0; i < length; ++i)
                        input.read();
                }
            }
        });
        server.start();
        serverPort = connector.getLocalPort();

        logger.debug(" => :{}", serverPort);
    }

    @After
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
        if (threadPool != null)
            threadPool.shutdownNow();
    }

    private Socket newClient() throws IOException, InterruptedException
    {
        Socket client = new Socket("localhost", serverPort);
        return client;
    }
    
    @Test
    @Ignore
    public void testServerCloseClientDoesNotClose() throws Exception
    {
        final Socket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        byte[] data = new byte[3 * 1024];
        Arrays.fill(data, (byte)'Y');
        String content = new String(data, "UTF-8");
        clientOutput.write(("" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + content.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                content).getBytes("UTF-8"));
        clientOutput.flush();
        
        System.out.println("Client output flushed");

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertThat(line,startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
            {
                break;
            }
        }
        
        System.out.println("Got response header");

        // Check that we did not spin
        int httpParseCount = httpParses.get();
        System.out.printf("Got %d http parses%n", httpParseCount);
        Assert.assertThat(httpParseCount, lessThan(50));
        
        // TODO: instead of sleeping, we should expect the connection being closed by the idle timeout
        // TODO: mechanism; unfortunately this now is not working, and this test fails because the idle
        // TODO: timeout will not trigger.
        TimeUnit.SECONDS.sleep(100);

        closeClient(client);
    }
    
    private void closeClient(Socket client) throws IOException
    {
        client.close();
    }
}
