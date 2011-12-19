package org.eclipse.jetty.client;

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.eclipse.jetty.toolchain.test.IO;
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
    private final AtomicInteger httpRequests = new AtomicInteger();
    private ExecutorService threadPool;
    private Server server;
    private int serverPort;
    private final AtomicReference<EndPoint> serverEndPoint = new AtomicReference<EndPoint>();

    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
        server = new Server();

        SelectChannelConnector connector = new SelectChannelConnector()
        {
            @Override
            protected AsyncConnection newConnection(SocketChannel channel, final AsyncEndPoint endPoint)
            {
                serverEndPoint.set(endPoint);
                return new org.eclipse.jetty.server.AsyncHttpConnection(this,endPoint,getServer())
                {
                    @Override
                    protected HttpParser newHttpParser(Buffers requestBuffers, EndPoint endPoint, HttpParser.EventHandler requestHandler)
                    {
                        return new HttpParser(requestBuffers,endPoint,requestHandler)
                        {
                            @Override
                            public int parseNext() throws IOException
                            {
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
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException,
                    ServletException
            {
                httpRequests.incrementAndGet();
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

        httpRequests.set(0);
        logger.debug(" => :{}",serverPort);
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
        Socket client = new Socket("localhost",serverPort);
        return client;
    }

    /**
     * Test that performs a normal http POST request, with connection:close.
     * Check that shutdownOutput is sufficient to close the server connection.
     */
    @Test
    public void testServerCloseClientDoesClose() throws Exception
    {
        // Log.getLogger("").setDebugEnabled(true);
        final Socket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        byte[] data = new byte[3 * 1024];
        Arrays.fill(data,(byte)'Y');
        String content = new String(data,"UTF-8");
        
        // The request section
        StringBuilder req = new StringBuilder();
        req.append("POST / HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Content-Type: text/plain\r\n");
        req.append("Content-Length: ").append(content.length()).append("\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");
        // and now, the POST content section.
        req.append(content);

        // Send request to server
        clientOutput.write(req.toString().getBytes("UTF-8"));
        clientOutput.flush();

        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader reader = null;
        try
        {
            in = client.getInputStream();
            isr = new InputStreamReader(in);
            reader = new BufferedReader(isr);

            // Read the response header
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
            Assert.assertEquals("one request handled",1,httpRequests.get());

            Assert.assertEquals("EOF received",-1,client.getInputStream().read());
            
            // shutdown the output
            client.shutdownOutput();

            // Check that we did not spin
            int httpParseCount = httpParses.get();
            Assert.assertThat(httpParseCount,lessThan(50));
            
            // Try to write another request (to prove that stream is closed)
            try
            {
                clientOutput.write(req.toString().getBytes("UTF-8"));
                clientOutput.flush();

                Assert.fail("Should not have been able to send a second POST request (connection: close)");
            }
            catch(SocketException e)
            {
            }
            
            Assert.assertEquals("one request handled",1,httpRequests.get());
        }
        finally
        {
            IO.close(reader);
            IO.close(isr);
            IO.close(in);
            closeClient(client);
        }
    }
    
    /**
     * Test that performs a seemingly normal http POST request, but with
     * a client that issues "connection: close", and then attempts to
     * write a second POST request.
     * <p>
     * The connection should be closed by the server
     */
    @Test
    public void testServerCloseClientMoreDataSent() throws Exception
    {
        // Log.getLogger("").setDebugEnabled(true);
        final Socket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        byte[] data = new byte[3 * 1024];
        Arrays.fill(data,(byte)'Y');
        String content = new String(data,"UTF-8");
        
        // The request section
        StringBuilder req = new StringBuilder();
        req.append("POST / HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Content-Type: text/plain\r\n");
        req.append("Content-Length: ").append(content.length()).append("\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");
        // and now, the POST content section.
        req.append(content);

        // Send request to server
        clientOutput.write(req.toString().getBytes("UTF-8"));
        clientOutput.flush();

        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader reader = null;
        try
        {
            in = client.getInputStream();
            isr = new InputStreamReader(in);
            reader = new BufferedReader(isr);

            // Read the response header
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

            Assert.assertEquals("EOF received",-1,client.getInputStream().read());
            Assert.assertEquals("one request handled",1,httpRequests.get());
            
            // Don't shutdown the output
            // client.shutdownOutput();
            
            // server side seeking EOF
            Assert.assertTrue("is open",serverEndPoint.get().isOpen());
            Assert.assertTrue("close sent",serverEndPoint.get().isOutputShutdown());
            Assert.assertFalse("close not received",serverEndPoint.get().isInputShutdown());
            

            // Check that we did not spin
            TimeUnit.SECONDS.sleep(1);
            int httpParseCount = httpParses.get();
            Assert.assertThat(httpParseCount,lessThan(50));
            

            // Write another request (which is ignored as the stream is closing), which causes real close.
            clientOutput.write(req.toString().getBytes("UTF-8"));
            clientOutput.flush();

            // Check that we did not spin
            TimeUnit.SECONDS.sleep(1);
            httpParseCount = httpParses.get();
            Assert.assertThat(httpParseCount,lessThan(50));
            

            // server side is closed
            Assert.assertFalse("is open",serverEndPoint.get().isOpen());
            Assert.assertTrue("close sent",serverEndPoint.get().isOutputShutdown());
            Assert.assertTrue("close not received",serverEndPoint.get().isInputShutdown());
            
            Assert.assertEquals("one request handled",1,httpRequests.get());

        }
        finally
        {
            IO.close(reader);
            IO.close(isr);
            IO.close(in);
            closeClient(client);
        }
    }
    
    
    /**
     * Test that performs a seemingly normal http POST request, but with
     * a client that issues "connection: close", and then does not close 
     * the connection after reading the response.
     * <p>
     * The connection should be closed by the server after a timeout.
     */
    @Test
    public void testServerCloseClientDoesNotClose() throws Exception
    {
        // Log.getLogger("").setDebugEnabled(true);
        final Socket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        byte[] data = new byte[3 * 1024];
        Arrays.fill(data,(byte)'Y');
        String content = new String(data,"UTF-8");
        
        // The request section
        StringBuilder req = new StringBuilder();
        req.append("POST / HTTP/1.1\r\n");
        req.append("Host: localhost\r\n");
        req.append("Content-Type: text/plain\r\n");
        req.append("Content-Length: ").append(content.length()).append("\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");
        // and now, the POST content section.
        req.append(content);

        // Send request to server
        clientOutput.write(req.toString().getBytes("UTF-8"));
        clientOutput.flush();

        InputStream in = null;
        InputStreamReader isr = null;
        BufferedReader reader = null;
        try
        {
            in = client.getInputStream();
            isr = new InputStreamReader(in);
            reader = new BufferedReader(isr);

            // Read the response header
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

            Assert.assertEquals("EOF received",-1,client.getInputStream().read());
            Assert.assertEquals("one request handled",1,httpRequests.get());
            
            // Don't shutdown the output
            // client.shutdownOutput();
            
            // server side seeking EOF
            Assert.assertTrue("is open",serverEndPoint.get().isOpen());
            Assert.assertTrue("close sent",serverEndPoint.get().isOutputShutdown());
            Assert.assertFalse("close not received",serverEndPoint.get().isInputShutdown());
            

            // Wait for the server idle timeout
            TimeUnit.SECONDS.sleep(3);
            int httpParseCount = httpParses.get();
            Assert.assertThat(httpParseCount,lessThan(50));

            // server side is closed
            Assert.assertFalse("is open",serverEndPoint.get().isOpen());
            Assert.assertTrue("close sent",serverEndPoint.get().isOutputShutdown());
            Assert.assertTrue("close not received",serverEndPoint.get().isInputShutdown());
            
            Assert.assertEquals("one request handled",1,httpRequests.get());
            
            
            // client will eventually get broken pipe if it keeps writing
            try
            {
                for (int i=0;i<1000;i++)
                {
                    clientOutput.write(req.toString().getBytes("UTF-8"));
                    clientOutput.flush(); 
                }
                Assert.fail("Client should have seen a broken pipe");
            }
            catch(IOException e)
            {
                // expected broken pipe
            }

        }
        finally
        {
            IO.close(reader);
            IO.close(isr);
            IO.close(in);
            closeClient(client);
        }
    }

    private void closeClient(Socket client) throws IOException
    {
        client.close();
    }
}
