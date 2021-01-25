//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.ssl;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ssl.SslBytesTest.TLSRecord.Type;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

// Other JREs have slight differences in how TLS work
// and this test expects a very specific TLS behavior.
@EnabledOnJre({JRE.JAVA_8, JRE.JAVA_11})
public class SslBytesServerTest extends SslBytesTest
{
    private final AtomicInteger sslFills = new AtomicInteger();
    private final AtomicInteger sslFlushes = new AtomicInteger();
    private final AtomicInteger httpParses = new AtomicInteger();
    private final AtomicReference<EndPoint> serverEndPoint = new AtomicReference<>();
    private final int idleTimeout = 2000;
    private ExecutorService threadPool;
    private Server server;
    private SslContextFactory sslContextFactory;
    private int serverPort;
    private SSLContext sslContext;
    private SimpleProxy proxy;
    private Runnable idleHook;

    @BeforeEach
    public void init() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        sslFills.set(0);
        sslFlushes.set(0);
        httpParses.set(0);
        serverEndPoint.set(null);

        File keyStore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        sslContextFactory = new SslContextFactory.Server();
        // This whole test is very specific to how TLS < 1.3 works.
        sslContextFactory.setIncludeProtocols("TLSv1.2");
        sslContextFactory.setKeyStorePath(keyStore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConnectionFactory httpFactory = new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return configure(new HttpConnection(getHttpConfiguration(), connector, endPoint, getHttpCompliance(), isRecordHttpComplianceViolations())
                {
                    @Override
                    protected HttpParser newHttpParser(HttpCompliance compliance)
                    {
                        return new HttpParser(newRequestHandler(), getHttpConfiguration().getRequestHeaderSize(), compliance)
                        {
                            @Override
                            public boolean parseNext(ByteBuffer buffer)
                            {
                                httpParses.incrementAndGet();
                                return super.parseNext(buffer);
                            }
                        };
                    }

                    @Override
                    protected boolean onReadTimeout(Throwable timeout)
                    {
                        final Runnable idleHook = SslBytesServerTest.this.idleHook;
                        if (idleHook != null)
                            idleHook.run();
                        return super.onReadTimeout(timeout);
                    }
                }, connector, endPoint);
            }
        };
        httpFactory.getHttpConfiguration().addCustomizer(new SecureRequestCustomizer());
        SslConnectionFactory sslFactory = new SslConnectionFactory(sslContextFactory, httpFactory.getProtocol())
        {
            @Override
            protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine)
                {
                    @Override
                    protected DecryptedEndPoint newDecryptedEndPoint()
                    {
                        return new DecryptedEndPoint()
                        {
                            @Override
                            public int fill(ByteBuffer buffer) throws IOException
                            {
                                sslFills.incrementAndGet();
                                return super.fill(buffer);
                            }

                            @Override
                            public boolean flush(ByteBuffer... appOuts) throws IOException
                            {
                                sslFlushes.incrementAndGet();
                                return super.flush(appOuts);
                            }
                        };
                    }
                };
            }
        };

        ServerConnector connector = new ServerConnector(server, null, null, null, 1, 1, sslFactory, httpFactory)
        {
            @Override
            protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                ChannelEndPoint endp = super.newEndPoint(channel, selectSet, key);
                serverEndPoint.set(endp);
                return endp;
            }
        };
        connector.setIdleTimeout(idleTimeout);
        connector.setPort(0);

        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException
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

        sslContext = sslContextFactory.getSslContext();

        threadPool = Executors.newCachedThreadPool();
        proxy = new SimpleProxy(threadPool, "localhost", serverPort);
        proxy.start();
        logger.info("proxy:{} <==> server:{}", proxy.getPort(), serverPort);
    }

    @AfterEach
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

        Future<Object> handshake = threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        assertNotNull(record);
        proxy.flushToServer(record);

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        assertNotNull(record);
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromClient();
        assertNotNull(record);
        proxy.flushToServer(record);

        // Client Done
        record = proxy.readFromClient();
        assertNotNull(record);
        proxy.flushToServer(record);

        // Change Cipher Spec
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        assertNull(handshake.get(5, TimeUnit.SECONDS));

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        closeClient(client);
    }

    @Test
    public void testHandshakeWithResumedSessionThenClose() throws Exception
    {
        // First socket will establish the SSL session
        SSLSocket client1 = newClient();
        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client1.startHandshake();
        client1.close();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));
        int proxyPort = proxy.getPort();
        proxy.stop();

        proxy = new SimpleProxy(threadPool, proxyPort, "localhost", serverPort);
        proxy.start();
        logger.info("proxy:{} <==> server:{}", proxy.getPort(), serverPort);

        final SSLSocket client2 = newClient(proxy);

        threadPool.submit(() ->
        {
            client2.startHandshake();
            return null;
        });

        // Client Hello with SessionID
        TLSRecord record = proxy.readFromClient();
        assertNotNull(record);
        proxy.flushToServer(record);

        // Server Hello
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        // Change Cipher Spec
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        assertNotNull(record);
        // Client Done
        TLSRecord doneRecord = proxy.readFromClient();
        assertNotNull(doneRecord);
        // Close
        client2.close();
        TLSRecord closeRecord = proxy.readFromClient();
        assertNotNull(closeRecord);
        assertEquals(TLSRecord.Type.ALERT, closeRecord.getType());
        // Flush to server Client Key Exchange + Client Done + Close in one chunk
        byte[] recordBytes = record.getBytes();
        byte[] doneBytes = doneRecord.getBytes();
        byte[] closeRecordBytes = closeRecord.getBytes();
        byte[] chunk = new byte[recordBytes.length + doneBytes.length + closeRecordBytes.length];
        System.arraycopy(recordBytes, 0, chunk, 0, recordBytes.length);
        System.arraycopy(doneBytes, 0, chunk, recordBytes.length, doneBytes.length);
        System.arraycopy(closeRecordBytes, 0, chunk, recordBytes.length + doneBytes.length, closeRecordBytes.length);
        proxy.flushToServer(0, chunk);
        // Close the raw socket
        proxy.flushToServer(null);

        // Expect the server to send a TLS Alert.
        record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.ALERT, record.getType());
        record = proxy.readFromServer();
        assertNull(record);

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));
    }

    @Test
    public void testHandshakeWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        Future<Object> handshake = threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
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
        assertNotNull(record);
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        assertNotNull(record);
        proxy.flushToClient(record);

        assertNull(handshake.get(5, TimeUnit.SECONDS));

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(40));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

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
        assertNull(record, String.valueOf(record));
        proxy.flushToServer(record);

        // Socket close
        record = proxy.readFromServer();
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }
    }

    @Test
    public void testClientHelloIncompleteThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
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
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    public void testClientHelloThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        assertNotNull(record);
        proxy.flushToServer(record);

        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    public void testHandshakeThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    public void testRequestIncompleteThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
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
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    public void testRequestResponse() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        closeClient(client);
    }

    @Test
    public void testHandshakeAndRequestOneByteAtATime() throws Exception
    {
        final SSLSocket client = newClient();

        Future<Object> handshake = threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Client Hello
        TLSRecord record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(5, b);
        }

        // Server Hello + Certificate + Server Done
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        // Client Key Exchange
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(5, b);
        }

        // Change Cipher Spec
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(5, b);
        }

        // Client Done
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(5, b);
        }

        // Change Cipher Spec
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        // Server Done
        record = proxy.readFromServer();
        proxy.flushToClient(record);

        assertNull(handshake.get(1, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(5, b);
        }
        assertNull(request.get(1, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(1000);
        assertThat(sslFills.get(), Matchers.lessThan(2000));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        // An average of 958 httpParses is seen in standard Oracle JDK's
        // An average of 1183 httpParses is seen in OpenJDK JVMs.
        assertThat(httpParses.get(), Matchers.lessThan(2000));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        for (byte b : record.getBytes())
        {
            proxy.flushToServer(5, b);
        }
        // Socket close
        record = proxy.readFromClient();
        assertNull(record, String.valueOf(record));
        proxy.flushToServer(record);

        // Socket close
        record = proxy.readFromServer();
        // Raw close or alert
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }
    }

    @Test
    @EnabledOnOs(LINUX) // See next test on why we only run in Linux
    public void testRequestWithCloseAlertAndShutdown() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        assertNull(request.get(5, TimeUnit.SECONDS));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        proxy.flushToServer(record);
        // Socket close
        record = proxy.readFromClient();
        assertNull(record, String.valueOf(record));
        proxy.flushToServer(record);

        // Expect response from server
        // SSLSocket is limited and we cannot read the response, but we make sure
        // it is application data and not a close alert
        record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Socket close
        record = proxy.readFromServer();
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));
    }

    @Test
    @EnabledOnOs(LINUX)
    public void testRequestWithCloseAlert() throws Exception
    {
        // Currently we are ignoring this test on anything other then linux
        // http://tools.ietf.org/html/rfc2246#section-7.2.1

        // TODO (react to this portion which seems to allow win/mac behavior)
        // It is required that the other party respond with a close_notify alert of its own
        // and close down the connection immediately, discarding any pending writes. It is not
        // required for the initiator of the close to wait for the responding
        // close_notify alert before closing the read side of the connection.

        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record);
        assertNull(request.get(5, TimeUnit.SECONDS));

        client.close();

        // Close Alert
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.ALERT, record.getType());
        proxy.flushToServer(record);

        // Do not close the raw socket yet

        // Expect response from server
        // SSLSocket is limited and we cannot read the response, but we make sure
        // it is application data and not a close alert
        record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Socket close
        record = proxy.readFromServer();
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        // Socket close
        record = proxy.readFromClient();
        assertNull(record, String.valueOf(record));
        proxy.flushToServer(record);
    }

    @Test
    public void testRequestWithRawClose() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record);
        assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Close the raw socket, this generates a truncation attack
        proxy.flushToServer(null);

        // Expect raw close from server OR ALERT
        record = proxy.readFromServer();
        // TODO check that this is OK?
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    public void testRequestWithImmediateRawClose() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record, 0);
        // Close the raw socket, this generates a truncation attack
        proxy.flushToServer(null);
        assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Expect raw close from server
        record = proxy.readFromServer();
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    @DisabledOnOs(WINDOWS) // Don't run on Windows (buggy JVM)
    public void testRequestWithBigContentWriteBlockedThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, StandardCharsets.UTF_8);
        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET /echo HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "\r\n" +
                    content).getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Nine TLSRecords will be generated for the request
        for (int i = 0; i < 9; ++i)
        {
            // Application data
            TLSRecord record = proxy.readFromClient();
            assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record, 0);
        }
        assertNull(request.get(5, TimeUnit.SECONDS));

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
        assertThat(sslFills.get(), Matchers.lessThan(40));
        assertThat(sslFlushes.get(), Matchers.lessThan(40));
        assertThat(httpParses.get(), Matchers.lessThan(50));

        client.close();
    }

    @Test
    @DisabledOnOs(WINDOWS) // Don't run on Windows (buggy JVM)
    public void testRequestWithBigContentReadBlockedThenReset() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, StandardCharsets.UTF_8);
        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET /echo_suppress_exception HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "\r\n" +
                    content).getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Nine TLSRecords will be generated for the request,
        // but we write only 5 of them, so the server goes in read blocked state
        for (int i = 0; i < 5; ++i)
        {
            // Application data
            TLSRecord record = proxy.readFromClient();
            assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record, 0);
        }
        assertNull(request.get(5, TimeUnit.SECONDS));

        // The server should be read blocked, and we send a RST
        TimeUnit.MILLISECONDS.sleep(500);
        proxy.sendRSTToServer();

        // Wait a while to detect spinning
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(40));
        assertThat(sslFlushes.get(), Matchers.lessThan(40));
        assertThat(httpParses.get(), Matchers.lessThan(50));

        client.close();
    }

    @Test
    public void testRequestResponseServerIdleTimeoutClientResets() throws Exception
    {
        SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        proxy.flushToServer(record);
        assertNull(request.get(5, TimeUnit.SECONDS));

        // Application data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Wait for the server idle timeout.
        Thread.sleep(idleTimeout);

        // We expect that the server sends the TLS Alert.
        record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.ALERT, record.getType());

        // Send a RST to the server.
        proxy.sendRSTToServer();

        // Wait for the RST to be processed by the server.
        Thread.sleep(1000);

        // The server EndPoint must be closed.
        assertFalse(serverEndPoint.get().isOpen());

        client.close();
    }

    @Test
    @EnabledOnOs(LINUX) // see message below
    public void testRequestWithCloseAlertWithSplitBoundary() throws Exception
    {
        // currently we are ignoring this test on anything other then linux

        // http://tools.ietf.org/html/rfc2246#section-7.2.1

        // TODO (react to this portion which seems to allow win/mac behavior)
        //It is required that the other party respond with a close_notify alert of its own
        //and close down the connection immediately, discarding any pending writes. It is not
        //required for the initiator of the close to wait for the responding
        //close_notify alert before closing the read side of the connection.

        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord dataRecord = proxy.readFromClient();
        assertNull(request.get(5, TimeUnit.SECONDS));

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

        // Send the other half of the close alert bytes
        bytes = new byte[closeBytes.length - closeBytes.length / 2];
        System.arraycopy(closeBytes, closeBytes.length / 2, bytes, 0, bytes.length);
        proxy.flushToServer(100, bytes);

        // Do not close the raw socket yet

        // Expect response from server
        // SSLSocket is limited and we cannot read the response, but we make sure
        // it is application data and not a close alert
        TLSRecord record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        // Socket close
        record = proxy.readFromServer();
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);

            // Now should be a raw close
            record = proxy.readFromServer();
            assertNull(record, String.valueOf(record));
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));
    }

    @Test
    public void testRequestWithContentWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        final String content = "0123456789ABCDEF";

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "POST / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "\r\n" +
                    content).getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Application data
        TLSRecord record = proxy.readFromClient();
        assertNull(request.get(5, TimeUnit.SECONDS));
        byte[] chunk1 = new byte[2 * record.getBytes().length / 3];
        System.arraycopy(record.getBytes(), 0, chunk1, 0, chunk1.length);
        proxy.flushToServer(100, chunk1);

        byte[] chunk2 = new byte[record.getBytes().length - chunk1.length];
        System.arraycopy(record.getBytes(), chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk2);

        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        closeClient(client);
    }

    @Test
    public void testRequestWithBigContentWithSplitBoundary() throws Exception
    {
        final SSLSocket client = newClient();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Use a content that is larger than the TLS record which is 2^14 (around 16k)
        byte[] data = new byte[128 * 1024];
        Arrays.fill(data, (byte)'X');
        final String content = new String(data, StandardCharsets.UTF_8);

        Future<Object> request = threadPool.submit(() ->
        {
            OutputStream clientOutput = client.getOutputStream();
            clientOutput.write((
                "POST / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: " + content.length() + "\r\n" +
                    "\r\n" +
                    content).getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
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
        assertThat(sslFills.get(), Matchers.lessThan(100));
        assertThat(sslFlushes.get(), Matchers.lessThan(50));
        assertThat(httpParses.get(), Matchers.lessThan(100));

        assertNull(request.get(5, TimeUnit.SECONDS));

        TLSRecord record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(100));
        assertThat(sslFlushes.get(), Matchers.lessThan(50));
        assertThat(httpParses.get(), Matchers.lessThan(100));

        closeClient(client);
    }

    @Test
    public void testRequestWithContentWithRenegotiationInMiddleOfContentWhenRenegotiationIsForbidden() throws Exception
    {
        assumeJavaVersionSupportsTLSRenegotiations();

        sslContextFactory.setRenegotiationAllowed(false);

        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data1 = new byte[1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, StandardCharsets.UTF_8);
        byte[] data2 = new byte[1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, StandardCharsets.UTF_8);

        // Write only part of the body
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write((
            "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes(StandardCharsets.UTF_8));
        clientOutput.flush();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Renegotiation not allowed, server has closed
        loop:
        while (true)
        {
            record = proxy.readFromServer();
            if (record == null)
                break;
            switch (record.getType())
            {
                case APPLICATION:
                    fail("application data not allows after renegotiate");
                    return; // this is just to avoid checkstyle warning
                case ALERT:
                    break loop;
                default:
                    continue;
            }
        }
        assertEquals(TLSRecord.Type.ALERT, record.getType());
        proxy.flushToClient(record);

        record = proxy.readFromServer();
        assertNull(record);

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(50));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(50));

        client.close();
    }

    @Test
    public void testRequestWithBigContentWithRenegotiationInMiddleOfContent() throws Exception
    {
        assumeJavaVersionSupportsTLSRenegotiations();

        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Use a content that is larger than the TLS record which is 2^14 (around 16k)
        byte[] data1 = new byte[80 * 1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, StandardCharsets.UTF_8);
        byte[] data2 = new byte[48 * 1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, StandardCharsets.UTF_8);

        // Write only part of the body
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write((
            "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes(StandardCharsets.UTF_8));
        clientOutput.flush();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        Future<Object> renegotiation = threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Change Cipher
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Trigger a read to have the client write the final renegotiation steps
        client.setSoTimeout(100);

        assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());

        // Renegotiation Change Cipher
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToServer(record);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToServer(record);

        assertNull(renegotiation.get(5, TimeUnit.SECONDS));

        // Write the rest of the request
        Future<Object> request = threadPool.submit(() ->
        {
            clientOutput.write(content2.getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Three TLSRecords will be generated for the remainder of the content
        for (int i = 0; i < 3; ++i)
        {
            // Application data
            record = proxy.readFromClient();
            proxy.flushToServer(record);
        }

        assertNull(request.get(5, TimeUnit.SECONDS));

        // Read response
        // Application Data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(50));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(50));

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
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Use a content that is larger than the TLS record which is 2^14 (around 16k)
        byte[] data1 = new byte[80 * 1024];
        Arrays.fill(data1, (byte)'X');
        String content1 = new String(data1, StandardCharsets.UTF_8);
        byte[] data2 = new byte[48 * 1024];
        Arrays.fill(data2, (byte)'Y');
        final String content2 = new String(data2, StandardCharsets.UTF_8);

        // Write only part of the body
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write((
            "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1).getBytes(StandardCharsets.UTF_8));
        clientOutput.flush();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Renegotiate
        Future<Object> renegotiation = threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Renegotiation Handshake
        TLSRecord record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        byte[] bytes = record.getBytes();
        byte[] chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        byte[] chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Change Cipher
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        proxy.flushToClient(record);

        // Renegotiation Handshake
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        proxy.flushToClient(record);

        // Trigger a read to have the client write the final renegotiation steps
        client.setSoTimeout(100);

        assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());

        // Renegotiation Change Cipher
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.CHANGE_CIPHER_SPEC, record.getType());
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
        proxy.flushToServer(100, chunk2);

        // Renegotiation Handshake
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.HANDSHAKE, record.getType());
        bytes = record.getBytes();
        chunk1 = new byte[2 * bytes.length / 3];
        System.arraycopy(bytes, 0, chunk1, 0, chunk1.length);
        chunk2 = new byte[bytes.length - chunk1.length];
        System.arraycopy(bytes, chunk1.length, chunk2, 0, chunk2.length);
        proxy.flushToServer(100, chunk1);
        // Do not write the second chunk now, but merge it with content, see below

        assertNull(renegotiation.get(5, TimeUnit.SECONDS));

        // Write the rest of the request
        Future<Object> request = threadPool.submit(() ->
        {
            clientOutput.write(content2.getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            return null;
        });

        // Three TLSRecords will be generated for the remainder of the content
        // Merge the last chunk of the renegotiation with the first data record
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
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
            assertEquals(TLSRecord.Type.APPLICATION, record.getType());
            proxy.flushToServer(record);
        }

        assertNull(request.get(5, TimeUnit.SECONDS));

        // Read response
        // Application Data
        record = proxy.readFromServer();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToClient(record);

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(50));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(100));

        closeClient(client);
    }

    @Test
    public void testServerShutdownOutputClientDoesNotCloseServerCloses() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        byte[] data = new byte[3 * 1024];
        Arrays.fill(data, (byte)'Y');
        String content = new String(data, StandardCharsets.UTF_8);
        automaticProxyFlow = proxy.startAutomaticFlow();
        clientOutput.write((
            "POST / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + content.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                content).getBytes(StandardCharsets.UTF_8));
        clientOutput.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull(line);
        assertTrue(line.startsWith("HTTP/1.1 200 "));
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        // Check client is at EOF
        assertEquals(-1, client.getInputStream().read());

        // Client should close the socket, but let's hold it open.

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        // The server has shutdown the output since the client sent a Connection: close
        // but the client does not close, so the server must idle timeout the endPoint.

        TimeUnit.MILLISECONDS.sleep(idleTimeout + idleTimeout / 2);

        assertFalse(serverEndPoint.get().isOpen());
    }

    @Test
    public void testPlainText() throws Exception
    {
        final SSLSocket client = newClient();

        threadPool.submit(() ->
        {
            client.startHandshake();
            return null;
        });

        // Instead of passing the Client Hello, we simulate plain text was passed in
        proxy.flushToServer(0, "GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));

        // We expect that the server sends the TLS Alert.
        TLSRecord record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.ALERT, record.getType());
        record = proxy.readFromServer();
        assertNull(record);

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(20));

        client.close();
    }

    @Test
    public void testRequestConcurrentWithIdleExpiration() throws Exception
    {
        final SSLSocket client = newClient();
        final OutputStream clientOutput = client.getOutputStream();
        final CountDownLatch latch = new CountDownLatch(1);

        idleHook = () ->
        {
            if (latch.getCount() == 0)
                return;
            try
            {
                // Send request
                clientOutput.write((
                    "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n").getBytes(StandardCharsets.UTF_8));
                clientOutput.flush();
                latch.countDown();
            }
            catch (Exception x)
            {
                // Latch won't trigger and test will fail
                x.printStackTrace();
            }
        };

        SimpleProxy.AutomaticFlow automaticProxyFlow = proxy.startAutomaticFlow();
        client.startHandshake();
        assertTrue(automaticProxyFlow.stop(5, TimeUnit.SECONDS));

        assertTrue(latch.await(idleTimeout * 2, TimeUnit.MILLISECONDS));

        // Be sure that the server sent an SSL close alert
        TLSRecord record = proxy.readFromServer();
        assertNotNull(record);
        assertEquals(TLSRecord.Type.ALERT, record.getType());

        // Write the request to the server, to simulate a request
        // concurrent with the SSL close alert
        record = proxy.readFromClient();
        assertEquals(TLSRecord.Type.APPLICATION, record.getType());
        proxy.flushToServer(record, 0);

        // Check that we did not spin
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(sslFills.get(), Matchers.lessThan(20));
        assertThat(sslFlushes.get(), Matchers.lessThan(20));
        assertThat(httpParses.get(), Matchers.lessThan(50));

        record = proxy.readFromServer();
        assertNull(record);

        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(((Dumpable)server.getConnectors()[0]).dump(), Matchers.not(Matchers.containsString("SCEP@")));
    }

    // TODO: Remove?  We are on JDK 1.8+ now.
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
            Assumptions.assumeTrue(Integer.parseInt(nano) > 21);
        }
    }

    private SSLSocket newClient() throws IOException, InterruptedException
    {
        return newClient(proxy);
    }

    private SSLSocket newClient(SimpleProxy proxy) throws IOException, InterruptedException
    {
        SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket("localhost", proxy.getPort());
        client.setUseClientMode(true);
        assertTrue(proxy.awaitClient(5, TimeUnit.SECONDS));
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
        assertNull(record, String.valueOf(record));
        proxy.flushToServer(record);

        // Socket close
        record = proxy.readFromServer();
        if (record != null)
        {
            assertEquals(record.getType(), Type.ALERT);
            record = proxy.readFromServer();
        }
        assertNull(record);
    }
}
