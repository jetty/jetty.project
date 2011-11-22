package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.Matchers.lessThan;

public class SslBytesServerTest
{
    private final Logger logger = Log.getLogger(getClass());
    private final AtomicInteger sslHandles = new AtomicInteger();
    private final AtomicInteger httpParses = new AtomicInteger();
    private ExecutorService threadPool;
    private Server server;
    private SSLContext sslContext;
    private SimpleProxy proxy;

    @Before
    public void startServer() throws Exception
    {
        logger.setDebugEnabled(true);

        threadPool = Executors.newCachedThreadPool();
        server = new Server();

        SslSelectChannelConnector connector = new SslSelectChannelConnector()
        {
            @Override
            protected SslConnection newSslConnection(AsyncEndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(engine, endPoint)
                {
                    @Override
                    public Connection handle() throws IOException
                    {
                        sslHandles.incrementAndGet();
                        return super.handle();
                    }
                };
            }

            @Override
            protected AsyncConnection newPlainConnection(SocketChannel channel, AsyncEndPoint endPoint)
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
                                httpParses.incrementAndGet();
                                return super.parseNext();
                            }
                        };
                    }
                };
            }
        };

//        connector.setPort(5870);
        connector.setPort(0);

        File keyStore = MavenTestingUtils.getTestResourceFile("keystore");
        SslContextFactory cf = connector.getSslContextFactory();
        cf.setKeyStorePath(keyStore.getAbsolutePath());
        cf.setKeyStorePassword("storepwd");
        cf.setKeyManagerPassword("keypwd");
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

        sslContext = cf.getSslContext();

        proxy = new SimpleProxy(threadPool, "localhost", connector.getLocalPort());
        proxy.start();
        logger.debug(":{} <==> :{}", proxy.getPort(), connector.getLocalPort());
    }

    @After
    public void stopServer() throws Exception
    {
        if (proxy != null)
            proxy.stop();
        if (server != null)
            server.stop();
        if (threadPool != null)
            threadPool.shutdownNow();
    }

    @Test
    public void testHandshake() throws Exception
    {
        final SSLSocket client = newClient();

        Future<Object> handshake = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                client.startHandshake();
                return null;
            }
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        Assert.assertNotNull(record);
        proxy.flushToServer(record);

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        Assert.assertNotNull(record);
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromClient();
        Assert.assertNotNull(record);
        proxy.flushToServer(record);

        // Client Done
        record = proxy.readFromClient();
        Assert.assertNotNull(record);
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        proxy.flushToClient(record);

        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    @Test
    public void testHandshakeWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        Future<Object> handshake = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                client.startHandshake();
                return null;
            }
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        byte[] bytes = record.getBytes();
        byte[] chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        byte[] chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);

        // Change Cipher Spec
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);

        // Client Done
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);

        // Change Cipher Spec
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        proxy.flushToClient(record);

        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);
        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Close Alert
        record = proxy.readFromServer();
        proxy.flushToClient(record);
        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    @Test
    public void testRequestResponse() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    @Test
    public void testHandshakeAndRequestOneByteAtATime() throws Exception
    {
        final SSLSocket client = newClient();

        Future<Object> handshake = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                client.startHandshake();
                return null;
            }
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(b);
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(b);
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Change Cipher Spec
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(b);
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Client Done
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(b);
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Change Cipher Spec
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        Assert.assertNull(handshake.get(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(b);
            TimeUnit.MILLISECONDS.sleep(50);
        }
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(750));
        Assert.assertThat(httpParses.get(), lessThan(150));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(b);
            TimeUnit.MILLISECONDS.sleep(50);
        }
        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Close Alert
        record = proxy.readFromServer();
        proxy.flushToClient(record);
        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    /**
     * TODO
     * Currently this test does not pass.
     * The problem is a mix of Java not being able to perform SSL half closes
     * (but SSL supporting it), and the current implementation in Jetty.
     * See the test below, that passes and whose only difference is that we
     * delay the output shutdown from the client.
     *
     * @throws Exception if the test fails
     */
    @Ignore
    @Test
    public void testRequestWithCloseAlertAndShutdown() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        proxy.flushToServer(record);
        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Expect response from server
        // SSLSocket is limited and we cannot read the response, but we make sure
        // it is application data and not a close alert
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Close Alert
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.ALERT, record.getType());
        // We can't forward to the client, its socket is already closed

        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    @Test
    public void testRequestWithCloseAlert() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        proxy.flushToServer(record);

        // Do not close the raw socket yet

        // Expect response from server
        // SSLSocket is limited and we cannot read the response, but we make sure
        // it is application data and not a close alert
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Close Alert
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.ALERT, record.getType());
        // We can't forward to the client, its socket is already closed

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    @Test
    public void testRequestWithRawClose() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Close the raw socket, this generates a truncation attack
        proxy.flushToServer((TLSRecord)null);

        // Expect raw close from server
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testRequestWithCloseAlertWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        TLSRecord dataRecord = proxy.readFromClient();
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        client.close();

        // Close Alert
        TLSRecord closeRecord = proxy.readFromClient();

        // Send request and half of the close alert bytes
        byte[] dataBytes = dataRecord.getBytes();
        byte[] closeBytes = closeRecord.getBytes();
        byte[] bytes = new byte[dataBytes.length + closeBytes.length / 2];
        System.arraycopy(dataBytes, 0, bytes, 0, dataBytes.length);
        System.arraycopy(closeBytes, 0, bytes, dataBytes.length, closeBytes.length / 2);
        proxy.flushToServer(bytes);

        TimeUnit.MILLISECONDS.sleep(100);

        bytes = new byte[closeBytes.length - closeBytes.length / 2];
        System.arraycopy(closeBytes, closeBytes.length / 2, bytes, 0, bytes.length);
        proxy.flushToServer(bytes);

        // Do not close the raw socket yet

        // Expect response from server
        // SSLSocket is limited and we cannot read the response, but we make sure
        // it is application data and not a close alert
        TLSRecord record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Close Alert
        record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.ALERT, record.getType());
        // We can't forward to the client, its socket is already closed

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    @Test
    public void testRequestWithContentWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        final String content = "0123456789ABCDEF";

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "POST / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: " + content.length() + "\r\n" +
                        "\r\n" +
                        content).getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));
        byte[] chunk1 = new byte[2 * record.getBytes().length / 3];
        System.arraycopy(record.getBytes(), 0, chunk1, 0, chunk1.length);
        proxy.flushToServer(chunk1);

        TimeUnit.MILLISECONDS.sleep(100);

        byte[] chunk2 = new byte[record.getBytes().length - chunk1.length];
        System.arraycopy(record.getBytes(), chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk2);

        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    @Test
    public void testRequestWithBigContentWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Use a content that is larger than the TLS record which is 2^14 (around 16k)
        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, "UTF-8");

        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "POST / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: " + content.length() + "\r\n" +
                        "\r\n" +
                        content).getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Nine TLSRecords will be generated for the request
        for (int i = 0; i < 9; ++i)
        {
            // Application data
            TLSRecord record = proxy.readFromClient();
            byte[] bytes = record.getBytes();
            byte[] chunk1 = new byte[2 * bytes.length / 3];
            System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
            byte[] chunk2 = new byte[bytes.length - chunk1.length];
            System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
            proxy.flushToServer(chunk1);
            TimeUnit.MILLISECONDS.sleep(100);
            proxy.flushToServer(chunk2);
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        TLSRecord record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    @Test
    public void testRequestWithBigContentWithRenegotiationInMiddleOfContent() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Use a content that is larger than the TLS record which is 2^14 (around 16k)
        byte[] data1 = new byte[80 * 1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, "UTF-8");
        byte[] data2 = new byte[48 * 1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, "UTF-8");

        // Write only part of the body
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write(("" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes("UTF-8"));
        clientOutput.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        Future<Object> renegotiation = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                client.startHandshake();
                return null;
            }
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Change Cipher
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Trigger a read to have the client write the final renegotiation steps
        client.setSoTimeout(100);
        try
        {
            client.getInputStream().read();
            Assert.fail();
        }
        catch (SocketTimeoutException x)
        {
            // Expected
        }

        // Renegotiation Change Cipher
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToServer(record);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        Assert.assertNull(renegotiation.get(5, TimeUnit.SECONDS));

        // Write the rest of the request
        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                clientOutput.write(content2.getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Three TLSRecords will be generated for the remainder of the content
        for (int i = 0; i < 3; ++i)
        {
            // Application data
            record = proxy.readFromClient();
            proxy.flushToServer(record);
        }

        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // Read response
        // Application Data
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    @Test
    public void testRequestWithBigContentWithRenegotiationInMiddleOfContentWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Use a content that is larger than the TLS record which is 2^14 (around 16k)
        byte[] data1 = new byte[80 * 1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, "UTF-8");
        byte[] data2 = new byte[48 * 1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, "UTF-8");

        // Write only part of the body
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write(("" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes("UTF-8"));
        clientOutput.flush();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        Future<Object> renegotiation = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                client.startHandshake();
                return null;
            }
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        byte[] bytes = record.getBytes();
        byte[] chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        byte[] chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Change Cipher
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Trigger a read to have the client write the final renegotiation steps
        client.setSoTimeout(100);
        try
        {
            client.getInputStream().read();
            Assert.fail();
        }
        catch (SocketTimeoutException x)
        {
            // Expected
        }

        // Renegotiation Change Cipher
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        proxy.flushToServer(chunk2);
        TimeUnit.MILLISECONDS.sleep(100);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(chunk1);
        TimeUnit.MILLISECONDS.sleep(100);
        // Do not write the second chunk now, but merge it with content, see below

        Assert.assertNull(renegotiation.get(5, TimeUnit.SECONDS));

        // Write the rest of the request
        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                clientOutput.write(content2.getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Three TLSRecords will be generated for the remainder of the content
        // Merge the last chunk of the renegotiation with the first data record
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        byte[] dataBytes = record.getBytes();
        byte[] mergedBytes = new byte[chunk2.length + dataBytes.length];
        System.arraycopy(chunk2, 0, mergedBytes, 0, chunk2.length);
        System.arraycopy(dataBytes, 0, mergedBytes, chunk2.length, dataBytes.length);
        proxy.flushToServer(mergedBytes);
        // Write the remaining 2 TLS records
        for (int i = 0; i < 2; ++i)
        {
            // Application data
            record = proxy.readFromClient();
            Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record);
        }

        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // Read response
        // Application Data
        record = proxy.readFromServer();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    private SSLSocket newClient() throws IOException, InterruptedException
    {
        SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", proxy.getPort());
        client.setUseClientMode(true);
        Assert.assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));
        return client;
    }

    private void closeClient(SSLSocket client) throws IOException
    {
        client.close();

        // Close Alert
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        // Socket close
        record = proxy.readFromClient();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToServer(record);

        // Close Alert
        record = proxy.readFromServer();
        proxy.flushToClient(record);
        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    public class SimpleProxy implements Runnable
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ExecutorService threadPool;
        private final String serverHost;
        private final int serverPort;
        private volatile ServerSocket serverSocket;
        private volatile Socket server;
        private volatile Socket client;

        public SimpleProxy(ExecutorService threadPool, String serverHost, int serverPort)
        {
            this.threadPool = threadPool;
            this.serverHost = serverHost;
            this.serverPort = serverPort;
        }

        public void start() throws Exception
        {
//            serverSocket = new ServerSocket(5871);
            serverSocket = new ServerSocket(0);
            Thread acceptor = new Thread(this);
            acceptor.start();
            server = new Socket(serverHost, serverPort);
        }

        public void stop() throws Exception
        {
            serverSocket.close();
        }

        public void run()
        {
            try
            {
                client = serverSocket.accept();
                latch.countDown();
            }
            catch (IOException x)
            {
                x.printStackTrace();
            }
        }

        public int getPort()
        {
            return serverSocket.getLocalPort();
        }

        public TLSRecord readFromClient() throws IOException
        {
            TLSRecord record = read(client);
            logger.debug("C --> P {}", record);
            return record;
        }

        private TLSRecord read(Socket socket) throws IOException
        {
            InputStream input = socket.getInputStream();
            int first = -2;
            while (true)
            {
                try
                {
                    socket.setSoTimeout(500);
                    first = input.read();
                    break;
                }
                catch (SocketTimeoutException x)
                {
                    if (Thread.currentThread().isInterrupted())
                        break;
                }
            }
            if (first == -2)
                throw new InterruptedIOException();
            else if (first == -1)
                return null;

            if (first >= 0x80)
            {
                // SSLv2 Record
                int hiLength = first & 0x3F;
                int loLength = input.read();
                int length = (hiLength << 8) + loLength;
                byte[] bytes = new byte[2 + length];
                bytes[0] = (byte)first;
                bytes[1] = (byte)loLength;
                return read(TLSRecord.Type.HANDSHAKE, input, bytes, 2, length);
            }
            else
            {
                // TLS Record
                int major = input.read();
                int minor = input.read();
                int hiLength = input.read();
                int loLength = input.read();
                int length = (hiLength << 8) + loLength;
                byte[] bytes = new byte[1 + 2 + 2 + length];
                bytes[0] = (byte)first;
                bytes[1] = (byte)major;
                bytes[2] = (byte)minor;
                bytes[3] = (byte)hiLength;
                bytes[4] = (byte)loLength;
                return read(TLSRecord.Type.from(first), input, bytes, 5, length);
            }
        }

        private TLSRecord read(TLSRecord.Type type, InputStream input, byte[] bytes, int offset, int length) throws IOException
        {
            while (length > 0)
            {
                int read = input.read(bytes, offset, length);
                if (read < 0)
                    throw new EOFException();
                offset += read;
                length -= read;
            }
            return new TLSRecord(type, bytes);
        }

        public void flushToServer(TLSRecord record) throws IOException
        {
            if (record == null)
            {
                server.shutdownOutput();
                if (client.isOutputShutdown())
                {
                    client.close();
                    server.close();
                }
            }
            else
            {
                flush(server, record.getBytes());
            }
        }

        public void flushToServer(byte... bytes) throws IOException
        {
            flush(server, bytes);
        }

        private void flush(Socket socket, byte... bytes) throws IOException
        {
            OutputStream output = socket.getOutputStream();
            output.write(bytes);
            output.flush();
        }

        public TLSRecord readFromServer() throws IOException
        {
            TLSRecord record = read(server);
            logger.debug("P <-- S {}", record);
            return record;
        }

        public void flushToClient(TLSRecord record) throws IOException
        {
            if (record == null)
            {
                client.shutdownOutput();
                if (server.isOutputShutdown())
                {
                    server.close();
                    client.close();
                }
            }
            else
            {
                flush(client, record.getBytes());
            }
        }

        public AutomaticFlow startAutomaticFlow() throws InterruptedException
        {
            final CountDownLatch startLatch = new CountDownLatch(2);
            final CountDownLatch stopLatch = new CountDownLatch(2);
            Future<Object> clientToServer = threadPool.submit(new Callable<Object>()
            {
                public Object call() throws Exception
                {
                    startLatch.countDown();
                    logger.debug("Automatic flow C --> S started");
                    try
                    {
                        while (true)
                        {
                            flushToServer(readFromClient());
                        }
                    }
                    catch (InterruptedIOException x)
                    {
                        return null;
                    }
                    finally
                    {
                        stopLatch.countDown();
                        logger.debug("Automatic flow C --> S finished");
                    }
                }
            });
            Future<Object> serverToClient = threadPool.submit(new Callable<Object>()
            {
                public Object call() throws Exception
                {
                    startLatch.countDown();
                    logger.debug("Automatic flow C <-- S started");
                    try
                    {
                        while (true)
                        {
                            flushToClient(readFromServer());
                        }
                    }
                    catch (InterruptedIOException x)
                    {
                        return null;
                    }
                    finally
                    {
                        stopLatch.countDown();
                        logger.debug("Automatic flow C <-- S finished");
                    }
                }
            });
            Assert.assertTrue(startLatch.await(5, TimeUnit.SECONDS));
            return new AutomaticFlow(stopLatch, clientToServer, serverToClient);
        }

        public boolean awaitClient(int time, TimeUnit unit) throws InterruptedException
        {
            return latch.await(time, unit);
        }

        public class AutomaticFlow
        {
            private final CountDownLatch stopLatch;
            private final Future<Object> clientToServer;
            private final Future<Object> serverToClient;

            public AutomaticFlow(CountDownLatch stopLatch, Future<Object> clientToServer, Future<Object> serverToClient)
            {
                this.stopLatch = stopLatch;
                this.clientToServer = clientToServer;
                this.serverToClient = serverToClient;
            }

            public boolean stop(long time, TimeUnit unit) throws InterruptedException
            {
                clientToServer.cancel(true);
                serverToClient.cancel(true);
                return stopLatch.await(time, unit);
            }
        }
    }

    public static class TLSRecord
    {
        private final Type type;
        private final byte[] bytes;

        public TLSRecord(Type type, byte[] bytes)
        {
            this.type = type;
            this.bytes = bytes;
        }

        public Type getType()
        {
            return type;
        }

        public byte[] getBytes()
        {
            return bytes;
        }

        @Override
        public String toString()
        {
            return "TLSRecord [" + type + "] " + bytes.length + " bytes";
        }

        public enum Type
        {
            CHANGE_CIPHER_SPEC(20), ALERT(21), HANDSHAKE(22), APPLICATION(23);

            private int code;

            private Type(int code)
            {
                this.code = code;
                Mapper.codes.put(this.code, this);
            }

            public static Type from(int code)
            {
                Type result = Mapper.codes.get(code);
                if (result == null)
                    throw new IllegalArgumentException("Invalid TLSRecord.Type " + code);
                return result;
            }

            private static class Mapper
            {
                private static final Map<Integer, Type> codes = new HashMap<Integer, Type>();
            }
        }
    }

}
