package org.eclipse.jetty.client;

import static org.hamcrest.Matchers.*;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.server.AsyncHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class SslBytesServerTest extends SslBytesTest
{
    private final AtomicInteger sslHandles = new AtomicInteger();
    private final AtomicInteger sslFlushes = new AtomicInteger();
    private final AtomicInteger httpParses = new AtomicInteger();
    private final AtomicReference<EndPoint> serverEndPoint = new AtomicReference<EndPoint>();
    private final int idleTimeout = 2000;
    private ExecutorService threadPool;
    private Server server;
    private int serverPort;
    private SSLContext sslContext;
    private SimpleProxy proxy;
    private Runnable idleHook;

    @Before
    public void init() throws Exception
    {
        threadPool = Executors.newCachedThreadPool();
        server = new Server();

        SslSelectChannelConnector connector = new SslSelectChannelConnector()
        {
            @Override
            protected SslConnection newSslConnection(AsyncEndPoint endPoint, SSLEngine engine)
            {
                serverEndPoint.set(endPoint);
                return new SslConnection(engine, endPoint)
                {
                    @Override
                    public Connection handle() throws IOException
                    {
                        sslHandles.incrementAndGet();
                        return super.handle();
                    }

                    @Override
                    protected SslEndPoint newSslEndPoint()
                    {
                        return new SslEndPoint()
                        {
                            @Override
                            public int flush(Buffer buffer) throws IOException
                            {
                                sslFlushes.incrementAndGet();
                                return super.flush(buffer);
                            }
                        };
                    }

                    @Override
                    public void onIdleExpired(long idleForMs)
                    {
                        final Runnable idleHook = SslBytesServerTest.this.idleHook;
                        if (idleHook != null)
                            idleHook.run();
                        super.onIdleExpired(idleForMs);
                    }
                };
            }

            @Override
            protected AsyncConnection newPlainConnection(SocketChannel channel, AsyncEndPoint endPoint)
            {
                return new AsyncHttpConnection(this, endPoint, getServer())
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
        connector.setMaxIdleTime(idleTimeout);

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
                try
                {
                    request.setHandled(true);
                    String contentLength = request.getHeader("Content-Length");
                    if (contentLength != null)
                    {
                        int length = Integer.parseInt(contentLength);
                        ServletInputStream input = httpRequest.getInputStream();
                        ServletOutputStream output = httpResponse.getOutputStream();
                        byte[] buffer = new byte[32 * 1024];
                        while (length > 0)
                        {
                            int read = input.read(buffer);
                            if (read < 0)
                                throw new EOFException();
                            length -= read;
                            if (target.startsWith("/echo"))
                                output.write(buffer, 0, read);
                        }
                    }
                }
                catch (IOException x)
                {
                    if (!(target.endsWith("suppress_exception")))
                        throw x;
                }
            }
        });
        server.start();
        serverPort = connector.getLocalPort();

        sslContext = cf.getSslContext();

        proxy = new SimpleProxy(threadPool, "localhost", serverPort);
        proxy.start();
        logger.debug(":{} <==> :{}", proxy.getPort(), serverPort);
    }

    @After
    public void destroy() throws Exception
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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
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
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

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
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

        // Change Cipher Spec
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

        // Client Done
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);
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
    public void testClientHelloIncompleteThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        threadPool.submit(new Callable<Object>()
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
        proxy.flushToServer(100, chunk1);

        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testClientHelloThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        threadPool.submit(new Callable<Object>()
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

        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testHandshakeThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testRequestIncompleteThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        threadPool.submit(new Callable<Object>()
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
        byte[] bytes = record.getBytes();
        byte[] chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        proxy.flushToServer(100, chunk1);

        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
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
            proxy.flushToServer(50, b);

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
            proxy.flushToServer(50, b);

        // Change Cipher Spec
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
            proxy.flushToServer(50, b);

        // Client Done
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
            proxy.flushToServer(50, b);

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
            proxy.flushToServer(50, b);
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
        TimeUnit.MILLISECONDS.sleep(1000);
        Assert.assertThat(sslHandles.get(), lessThan(750));
        Assert.assertThat(sslFlushes.get(), lessThan(750));
        // An average of 958 httpParses is seen in standard Oracle JDK's
        // An average of 1183 httpParses is seen in OpenJDK JVMs.
        Assert.assertThat(httpParses.get(), lessThan(1500));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
            proxy.flushToServer(50, b);
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
    public void testRequestWithCloseAlertAndShutdown() throws Exception
    {
        // See next test on why we only run in Linux
        Assume.assumeTrue(OS.IS_LINUX);

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

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        // Socket close
        record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);
        proxy.flushToClient(record);
    }

    @Test
    public void testRequestWithCloseAlert() throws Exception
    {
        // Currently we are ignoring this test on anything other then linux
        // http://tools.ietf.org/html/rfc2246#section-7.2.1

        // TODO (react to this portion which seems to allow win/mac behavior)
        // It is required that the other party respond with a close_notify alert of its own
        // and close down the connection immediately, discarding any pending writes. It is not
        // required for the initiator of the close to wait for the responding
        // close_notify alert before closing the read side of the connection.
        Assume.assumeTrue(OS.IS_LINUX);

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
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record);
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.ALERT, record.getType());
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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
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
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testRequestWithBigContentWriteBlockedThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, "UTF-8");
        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
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
            Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record, 0);
        }
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // We asked the server to echo back the data we sent
        // but we do not read it, thus causing a write interest
        // on the server.
        // However, we then simulate that the client resets the
        // connection, and this will cause an exception in the
        // server that is trying to write the data

        TimeUnit.MILLISECONDS.sleep(500);
        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testRequestWithBigContentReadBlockedThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, "UTF-8");
        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                OutputStream clientOutput = client.getOutputStream();
                clientOutput.write(("" +
                        "GET /echo_suppress_exception HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: " + content.length() + "\r\n" +
                        "\r\n" +
                        content).getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        // Nine TLSRecords will be generated for the request,
        // but we write only 5 of them, so the server goes in read blocked state
        for (int i = 0; i < 5; ++i)
        {
            // Application data
            TLSRecord record = proxy.readFromClient();
            Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record, 0);
        }
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // The server should be read blocked, and we send a RST
        TimeUnit.MILLISECONDS.sleep(500);
        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testRequestWithCloseAlertWithSplitBoundary() throws Exception
    {
        if ( !OS.IS_LINUX )
        {
            // currently we are ignoring this test on anything other then linux

            //http://tools.ietf.org/html/rfc2246#section-7.2.1

            // TODO (react to this portion which seems to allow win/mac behavior)
            //It is required that the other party respond with a close_notify alert of its own
            //and close down the connection immediately, discarding any pending writes. It is not
            //required for the initiator of the close to wait for the responding
            //close_notify alert before closing the read side of the connection.
            return;
        }

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
        proxy.flushToServer(100, bytes);

        bytes = new byte[closeBytes.length - closeBytes.length / 2];
        System.arraycopy(closeBytes, closeBytes.length / 2, bytes, 0, bytes.length);
        proxy.flushToServer(100, bytes);

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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
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
        proxy.flushToServer(100, chunk1);

        byte[] chunk2 = new byte[record.getBytes().length - chunk1.length];
        System.arraycopy(record.getBytes(), chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk2);

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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
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
            proxy.flushToServer(100, chunk1);
            proxy.flushToServer(100, chunk2);
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(150));

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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(150));

        closeClient(client);
    }

    @Test
    public void testRequestWithBigContentWithRenegotiationInMiddleOfContent() throws Exception
    {
        assumeJavaVersionSupportsTLSRenegotiations();

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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        closeClient(client);
    }

    @Test
    public void testRequestWithBigContentWithRenegotiationInMiddleOfContentWithSplitBoundary() throws Exception
    {
        assumeJavaVersionSupportsTLSRenegotiations();

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
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

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
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
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
        proxy.flushToServer(100, mergedBytes);
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
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(100));

        closeClient(client);
    }

    @Test
    public void testServerShutdownOutputClientDoesNotCloseServerCloses() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[3 * 1024];
        Arrays.fill(data, (byte)'Y');
        String content = new String(data, "UTF-8");
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write(("" +
                "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + content.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                content).getBytes("UTF-8"));
        clientOutput.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
        String line = reader.readLine();
        Assert.assertNotNull(line);
        Assert.assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Check client is at EOF
        Assert.assertEquals(-1,client.getInputStream().read());

        // Client should close the socket, but let's hold it open.

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        // The server has shutdown the output since the client sent a Connection: close
        // but the client does not close, so the server must idle timeout the endPoint.

        TimeUnit.MILLISECONDS.sleep(idleTimeout + idleTimeout/2);

        Assert.assertFalse(serverEndPoint.get().isOpen());
    }

    @Test
    public void testPlainText() throws Exception
    {
        final SSLSocket client = newClient();

        threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                client.startHandshake();
                return null;
            }
        });

        // Instead of passing the Client Hello, we simulate plain text was passed in
        proxy.flushToServer(0, "GET / HTTP/1.1\r\n".getBytes("UTF-8"));

        // We expect that the server closes the connection immediately
        TLSRecord record = proxy.readFromServer();
        Assert.assertNull(String.valueOf(record), record);

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        client.close();
    }

    @Test
    public void testRequestConcurrentWithIdleExpiration() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();
        final CountDownLatch latch = new CountDownLatch(1);

        idleHook = new Runnable()
        {
            public void run()
            {
                if (latch.getCount()==0)
                    return;
                try
                {
                    // Send request
                    clientOutput.write(("" +
                            "GET / HTTP/1.1\r\n" +
                            "Host: localhost\r\n" +
                            "\r\n").getBytes("UTF-8"));
                    clientOutput.flush();
                    latch.countDown();
                }
                catch (Exception x)
                {
                    // Latch won't trigger and test will fail
                    x.printStackTrace();
                }
            }
        };

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Assert.assertTrue(latch.await(idleTimeout * 2, TimeUnit.MILLISECONDS));

        // Be sure that the server sent a SSL close alert
        TLSRecord record = proxy.readFromServer();
        Assert.assertNotNull(record);
        Assert.assertEquals(TLSRecord.Type.ALERT, record.getType());

        // Write the request to the server, to simulate a request
        // concurrent with the SSL close alert
        record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record, 0);

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        //System.err.println(((Dumpable)server.getConnectors()[0]).dump());
        Assert.assertThat(((Dumpable)server.getConnectors()[0]).dump(),containsString("SCEP@"));
        
        completeClose(client);
        
        TimeUnit.MILLISECONDS.sleep(200);
        //System.err.println(((Dumpable)server.getConnectors()[0]).dump());
        Assert.assertThat(((Dumpable)server.getConnectors()[0]).dump(),not(containsString("SCEP@")));

    }
/*
    @Test
    public void testRequestWriteBlockedWithPipelinedRequest() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        Assert.assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, "UTF-8");
        Future<Object> request = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                clientOutput.write(("" +
                        "POST /echo HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
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
            Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record, 0);
        }
        Assert.assertNull(request.get(5, TimeUnit.SECONDS));

        // We do not read the big request to cause a write blocked on the server
        TimeUnit.MILLISECONDS.sleep(500);

        // Now send the pipelined request
        Future<Object> pipelined = threadPool.submit(new Callable<Object>()
        {
            public Object call() throws Exception
            {
                clientOutput.write(("" +
                        "GET /pipelined HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes("UTF-8"));
                clientOutput.flush();
                return null;
            }
        });

        TLSRecord record = proxy.readFromClient();
        Assert.assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record, 0);
        Assert.assertNull(pipelined.get(5, TimeUnit.SECONDS));

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        Assert.assertThat(sslHandles.get(), lessThan(20));
        Assert.assertThat(sslFlushes.get(), lessThan(20));
        Assert.assertThat(httpParses.get(), lessThan(50));

        Thread.sleep(5000);

//        closeClient(client);
    }
*/
    private void assumeJavaVersionSupportsTLSRenegotiations()
    {
        // Due to a security bug, TLS renegotiations were disabled in JDK 1.6.0_19-21
        // so we check the java version in order to avoid to fail the test.
        String javaVersion = System.getProperty("java.version");
        Pattern regexp = Pattern.compile("1\\.6\\.0_(\\d{2})");
        Matcher matcher = regexp.matcher(javaVersion);
        if (matcher.matches())
        {
            String nano = matcher.group(1);
            Assume.assumeThat(Integer.parseInt(nano), greaterThan(21));
        }
    }

    private SSLSocket newClient() throws IOException, InterruptedException
    {
        SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", proxy.getPort());
        client.setUseClientMode(true);
        Assert.assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));
        return client;
    }

    private void closeClient(SSLSocket client) throws Exception
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
    
    private void completeClose(SSLSocket client) throws Exception
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
        
    }
}
