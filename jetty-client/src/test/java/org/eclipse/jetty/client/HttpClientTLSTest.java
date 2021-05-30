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

package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTLSTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(SslContextFactory sslContextFactory, Handler handler) throws Exception
    {
        ExecutorThreadPool serverThreads = new ExecutorThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    private void startClient(SslContextFactory sslContextFactory) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(sslContextFactory);
        client.setExecutor(clientThreads);
        client.start();
    }

    private SslContextFactory.Server createServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configureSslContextFactory(sslContextFactory);
        return sslContextFactory;
    }

    private SslContextFactory.Client createClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        configureSslContextFactory(sslContextFactory);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testNoCommonTLSProtocol() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        serverTLSFactory.setIncludeProtocols("TLSv1.3");
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        clientTLSFactory.setIncludeProtocols("TLSv1.2");
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testNoCommonTLSCiphers() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        serverTLSFactory.setIncludeCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA");
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        clientTLSFactory.setExcludeCipherSuites(".*_SHA$");
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnServer() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        // TLS 1.1 protocol, but only TLS 1.2 ciphers.
        serverTLSFactory.setIncludeProtocols("TLSv1.1");
        serverTLSFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    // In JDK 11+, a mismatch on the client does not generate any bytes towards
    // the server, while in previous JDKs the client sends to the server the close_notify.
    // @EnabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
    @Disabled("No longer viable, TLS protocol behavior changed in 8u272")
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnClient() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        // TLS 1.1 protocol, but only TLS 1.2 ciphers.
        clientTLSFactory.setIncludeProtocols("TLSv1.1");
        clientTLSFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandshakeSucceeded() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                clientLatch.countDown();
            }
        });

        ContentResponse response = client.GET("https://localhost:" + connector.getLocalPort());
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    // Excluded in JDK 11+ because resumed sessions cannot be compared
    // using their session IDs even though they are resumed correctly.
    @EnabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
    @Test
    public void testHandshakeSucceededWithSessionResumption() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        AtomicReference<byte[]> serverSession = new AtomicReference<>();
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                serverSession.set(event.getSSLEngine().getSession().getId());
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);

        AtomicReference<byte[]> clientSession = new AtomicReference<>();
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                clientSession.set(event.getSSLEngine().getSession().getId());
            }
        });

        // First request primes the TLS session.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .header(HttpHeader.CONNECTION, "close")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertNotNull(serverSession.get());
        assertNotNull(clientSession.get());

        connector.removeBean(connector.getBean(SslHandshakeListener.class));
        client.removeBean(client.getBean(SslHandshakeListener.class));

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                if (Arrays.equals(serverSession.get(), event.getSSLEngine().getSession().getId()))
                    serverLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                if (Arrays.equals(clientSession.get(), event.getSSLEngine().getSession().getId()))
                    clientLatch.countDown();
            }
        });

        // Second request should have the same session ID.
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .header(HttpHeader.CONNECTION, "close")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    // Excluded in JDK 11+ because resumed sessions cannot be compared
    // using their session IDs even though they are resumed correctly.
    @EnabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
    @Test
    public void testClientRawCloseDoesNotInvalidateSession() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        clientTLSFactory.start();

        String host = "localhost";
        int port = connector.getLocalPort();
        Socket socket1 = new Socket(host, port);
        SSLSocket sslSocket1 = (SSLSocket)clientTLSFactory.getSslContext().getSocketFactory().createSocket(socket1, host, port, true);
        CountDownLatch handshakeLatch1 = new CountDownLatch(1);
        AtomicReference<byte[]> session1 = new AtomicReference<>();
        sslSocket1.addHandshakeCompletedListener(event ->
        {
            session1.set(event.getSession().getId());
            handshakeLatch1.countDown();
        });
        sslSocket1.startHandshake();
        assertTrue(handshakeLatch1.await(5, TimeUnit.SECONDS));

        // In TLS 1.3 the server sends a NewSessionTicket post-handshake message
        // to enable session resumption and without a read, the message is not processed.

        assertThrows(SocketTimeoutException.class, () ->
        {
            sslSocket1.setSoTimeout(1000);
            sslSocket1.getInputStream().read();
        });

        // The client closes abruptly.
        socket1.close();

        // Try again and compare the session ids.
        Socket socket2 = new Socket(host, port);
        SSLSocket sslSocket2 = (SSLSocket)clientTLSFactory.getSslContext().getSocketFactory().createSocket(socket2, host, port, true);
        CountDownLatch handshakeLatch2 = new CountDownLatch(1);
        AtomicReference<byte[]> session2 = new AtomicReference<>();
        sslSocket2.addHandshakeCompletedListener(event ->
        {
            session2.set(event.getSession().getId());
            handshakeLatch2.countDown();
        });
        sslSocket2.startHandshake();
        assertTrue(handshakeLatch2.await(5, TimeUnit.SECONDS));

        assertArrayEquals(session1.get(), session2.get());

        sslSocket2.close();
    }

    @Test
    public void testServerRawCloseDetectedByClient() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        serverTLSFactory.start();
        try (ServerSocket server = new ServerSocket(0))
        {
            QueuedThreadPool clientThreads = new QueuedThreadPool();
            clientThreads.setName("client");
            client = new HttpClient(createClientSslContextFactory())
            {
                @Override
                protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
                {
                    SslClientConnectionFactory ssl = (SslClientConnectionFactory)super.newSslClientConnectionFactory(sslContextFactory, connectionFactory);
                    ssl.setRequireCloseMessage(true);
                    return ssl;
                }
            };
            client.setExecutor(clientThreads);
            client.start();

            CountDownLatch latch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .send(result ->
                {
                    assertThat(result.getResponseFailure(), instanceOf(SSLException.class));
                    latch.countDown();
                });

            try (Socket socket = server.accept())
            {
                SSLSocket sslSocket = (SSLSocket)serverTLSFactory.getSslContext().getSocketFactory().createSocket(socket, null, socket.getPort(), true);
                sslSocket.setUseClientMode(false);
                BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
                while (true)
                {
                    String line = reader.readLine();
                    if (StringUtil.isEmpty(line))
                        break;
                }

                // If the response is Content-Length delimited, the lack of
                // the TLS Close Message is fine because the application
                // will see a EOFException anyway: the Content-Length and
                // the actual content bytes count won't match.
                // If the response is connection delimited, the lack of the
                // TLS Close Message is bad because the application will
                // see a successful response, but with truncated content.

                // Verify that by requiring the TLS Close Message we get
                // a response failure.

                byte[] half = new byte[8];
                String response = "HTTP/1.1 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
                OutputStream output = sslSocket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.write(half);
                output.flush();
                // Simulate a truncation attack by raw closing
                // the socket in the try-with-resources block end.
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testHostNameVerificationFailure() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        // Make sure the host name is not verified at the TLS level.
        clientTLSFactory.setEndpointIdentificationAlgorithm(null);
        // Add host name verification after the TLS handshake.
        clientTLSFactory.setHostnameVerifier((host, session) -> false);
        startClient(clientTLSFactory);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .send(result ->
            {
                Throwable failure = result.getFailure();
                if (failure instanceof SSLPeerUnverifiedException)
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNeverUsedConnectionThenServerIdleTimeout() throws Exception
    {
        long idleTimeout = 2000;

        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        AtomicLong serverBytes = new AtomicLong();
        SslConnectionFactory ssl = new SslConnectionFactory(serverTLSFactory, http.getProtocol())
        {
            @Override
            protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                {
                    @Override
                    protected int networkFill(ByteBuffer input) throws IOException
                    {
                        int n = super.networkFill(input);
                        if (n > 0)
                            serverBytes.addAndGet(n);
                        return n;
                    }
                };
            }
        };
        connector = new ServerConnector(server, 1, 1, ssl, http);
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        AtomicLong clientBytes = new AtomicLong();
        client = new HttpClient(clientTLSFactory)
        {
            @Override
            protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                        {
                            @Override
                            protected int networkFill(ByteBuffer input) throws IOException
                            {
                                int n = super.networkFill(input);
                                if (n > 0)
                                    clientBytes.addAndGet(n);
                                return n;
                            }
                        };
                    }
                };
            }
        };
        client.setExecutor(clientThreads);
        client.start();

        // Create a connection but don't use it.
        String scheme = HttpScheme.HTTPS.asString();
        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        // Trigger the creation of a new connection, but don't use it.
        ConnectionPoolHelper.tryCreate(connectionPool);
        // Verify that the connection has been created.
        while (true)
        {
            Thread.sleep(50);
            if (connectionPool.getConnectionCount() == 1)
                break;
        }

        // Wait for the server to idle timeout the connection.
        Thread.sleep(idleTimeout + idleTimeout / 2);

        // The connection should be gone from the connection pool.
        assertEquals(0, connectionPool.getConnectionCount(), connectionPool.dump());
        assertEquals(0, serverBytes.get());
        assertEquals(0, clientBytes.get());
    }

    @Test
    public void testNeverUsedConnectionThenClientIdleTimeout() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        AtomicLong serverBytes = new AtomicLong();
        SslConnectionFactory ssl = new SslConnectionFactory(serverTLSFactory, http.getProtocol())
        {
            @Override
            protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                {
                    @Override
                    protected int networkFill(ByteBuffer input) throws IOException
                    {
                        int n = super.networkFill(input);
                        if (n > 0)
                            serverBytes.addAndGet(n);
                        return n;
                    }
                };
            }
        };
        connector = new ServerConnector(server, 1, 1, ssl, http);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        long idleTimeout = 2000;

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        AtomicLong clientBytes = new AtomicLong();
        client = new HttpClient(clientTLSFactory)
        {
            @Override
            protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                        {
                            @Override
                            protected int networkFill(ByteBuffer input) throws IOException
                            {
                                int n = super.networkFill(input);
                                if (n > 0)
                                    clientBytes.addAndGet(n);
                                return n;
                            }
                        };
                    }
                };
            }
        };
        client.setIdleTimeout(idleTimeout);
        client.setExecutor(clientThreads);
        client.start();

        // Create a connection but don't use it.
        String scheme = HttpScheme.HTTPS.asString();
        String host = "localhost";
        int port = connector.getLocalPort();
        HttpDestination destination = (HttpDestination)client.getDestination(scheme, host, port);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        // Trigger the creation of a new connection, but don't use it.
        ConnectionPoolHelper.tryCreate(connectionPool);
        // Verify that the connection has been created.
        while (true)
        {
            Thread.sleep(50);
            if (connectionPool.getConnectionCount() == 1)
                break;
        }

        // Wait for the client to idle timeout the connection.
        Thread.sleep(idleTimeout + idleTimeout / 2);

        // The connection should be gone from the connection pool.
        assertEquals(0, connectionPool.getConnectionCount(), connectionPool.dump());
        assertEquals(0, serverBytes.get());
        assertEquals(0, clientBytes.get());
    }

    @Test
    public void testSSLEngineClosedDuringHandshake() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(clientTLSFactory)
        {
            @Override
            protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                        {
                            @Override
                            protected SSLEngineResult wrap(SSLEngine sslEngine, ByteBuffer[] input, ByteBuffer output) throws SSLException
                            {
                                sslEngine.closeOutbound();
                                return super.wrap(sslEngine, input, output);
                            }
                        };
                    }
                };
            }
        };
        client.setExecutor(clientThreads);
        client.start();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .send());
        Throwable cause = failure.getCause();
        assertThat(cause, Matchers.instanceOf(SSLHandshakeException.class));
    }

    @Test
    public void testTLSLargeFragments() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        SslConnectionFactory ssl = new SslConnectionFactory(serverTLSFactory, http.getProtocol())
        {
            @Override
            protected SslConnection newSslConnection(Connector connector, EndPoint endPoint, SSLEngine engine)
            {
                return new SslConnection(connector.getByteBufferPool(), connector.getExecutor(), endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                {
                    @Override
                    protected SSLEngineResult unwrap(SSLEngine sslEngine, ByteBuffer input, ByteBuffer output) throws SSLException
                    {
                        int inputBytes = input.remaining();
                        SSLEngineResult result = super.unwrap(sslEngine, input, output);
                        if (inputBytes == 5)
                            serverLatch.countDown();
                        return result;
                    }
                };
            }
        };
        connector = new ServerConnector(server, 1, 1, ssl, http);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        long idleTimeout = 2000;

        CountDownLatch clientLatch = new CountDownLatch(1);
        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(clientTLSFactory)
        {
            @Override
            protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
            {
                if (sslContextFactory == null)
                    sslContextFactory = getSslContextFactory();
                return new SslClientConnectionFactory(sslContextFactory, getByteBufferPool(), getExecutor(), connectionFactory)
                {
                    @Override
                    protected SslConnection newSslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine engine)
                    {
                        return new SslConnection(byteBufferPool, executor, endPoint, engine, isDirectBuffersForEncryption(), isDirectBuffersForDecryption())
                        {
                            @Override
                            protected SSLEngineResult wrap(SSLEngine sslEngine, ByteBuffer[] input, ByteBuffer output) throws SSLException
                            {
                                try
                                {
                                    clientLatch.countDown();
                                    assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
                                    return super.wrap(sslEngine, input, output);
                                }
                                catch (InterruptedException x)
                                {
                                    throw new SSLException(x);
                                }
                            }
                        };
                    }
                };
            }
        };
        client.setIdleTimeout(idleTimeout);
        client.setExecutor(clientThreads);
        client.start();

        String host = "localhost";
        int port = connector.getLocalPort();

        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest(host, port)
            .scheme(HttpScheme.HTTPS.asString())
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                responseLatch.countDown();
            });
        // Wait for the TLS buffers to be acquired by the client, then the
        // HTTP request will be paused waiting for the TLS buffer to be expanded.
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        // Send the large frame bytes that will enlarge the TLS buffers.
        try (Socket socket = new Socket(host, port))
        {
            OutputStream output = socket.getOutputStream();
            byte[] largeFrameBytes = new byte[5];
            largeFrameBytes[0] = 22; // Type = handshake
            largeFrameBytes[1] = 3; // Major TLS version
            largeFrameBytes[2] = 3; // Minor TLS version
            // Frame length is 0x7FFF == 32767, i.e. a "large fragment".
            // Maximum allowed by RFC 8446 is 16384, but SSLEngine supports up to 33093.
            largeFrameBytes[3] = 0x7F; // Length hi byte
            largeFrameBytes[4] = (byte)0xFF; // Length lo byte
            output.write(largeFrameBytes);
            output.flush();
            // Just close the connection now, the large frame
            // length was enough to trigger the buffer expansion.
        }

        // The HTTP request will resume and be forced to handle the TLS buffer expansion.
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDefaultNonDomainSNI() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStorePath("src/test/resources/keystore_sni_non_domain.p12");
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setSNISelector((keyType, issuers, session, sniHost, certificates) ->
        {
            // Java clients don't send SNI by default if it's not a domain.
            assertNull(sniHost);
            return serverTLS.sniSelect(keyType, issuers, session, sniHost, certificates);
        });
        startServer(serverTLS, new EmptyServerHandler());

        SslContextFactory.Client clientTLS = new SslContextFactory.Client();
        // Trust any certificate received by the server.
        clientTLS.setTrustStorePath("src/test/resources/keystore_sni_non_domain.p12");
        clientTLS.setTrustStorePassword("storepwd");
        // Disable TLS-level hostName verification, as we may receive a random certificate.
        clientTLS.setEndpointIdentificationAlgorithm(null);
        startClient(clientTLS);

        // Host is "localhost" which is not a domain, so the JDK won't send SNI.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testForcedNonDomainSNI() throws Exception
    {
        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStorePath("src/test/resources/keystore_sni_non_domain.p12");
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setSNISelector((keyType, issuers, session, sniHost, certificates) ->
        {
            // We have forced the client to send the non-domain SNI.
            assertNotNull(sniHost);
            return serverTLS.sniSelect(keyType, issuers, session, sniHost, certificates);
        });
        startServer(serverTLS, new EmptyServerHandler());

        SslContextFactory.Client clientTLS = new SslContextFactory.Client();
        // Trust any certificate received by the server.
        clientTLS.setTrustStorePath("src/test/resources/keystore_sni_non_domain.p12");
        clientTLS.setTrustStorePassword("storepwd");
        // Force TLS-level hostName verification, as we want to receive the correspondent certificate.
        clientTLS.setEndpointIdentificationAlgorithm("HTTPS");
        startClient(clientTLS);

        clientTLS.setSNIProvider(SslContextFactory.Client.SniProvider.NON_DOMAIN_SNI_PROVIDER);

        // Send a request with SNI "localhost", we should get the certificate at alias=localhost.
        ContentResponse response1 = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .send();
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        // Send a request with SNI "127.0.0.1", we should get the certificate at alias=ip.
        ContentResponse response2 = client.newRequest("127.0.0.1", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());

        if (Net.isIpv6InterfaceAvailable())
        {
            // Send a request with SNI "[::1]", we should get the certificate at alias=ip.
            ContentResponse response3 = client.newRequest("[::1]", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .send();

            assertEquals(HttpStatus.OK_200, response3.getStatus());
        }
    }

    @Test
    public void testBytesInBytesOut() throws Exception
    {
        // Two connections will be closed: SslConnection and HttpConnection.
        // Two on the server, two on the client.
        CountDownLatch latch = new CountDownLatch(4);
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());
        ConnectionStatistics serverStats = new ConnectionStatistics()
        {
            @Override
            public void onClosed(Connection connection)
            {
                super.onClosed(connection);
                latch.countDown();
            }
        };
        connector.addManaged(serverStats);

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);
        ConnectionStatistics clientStats = new ConnectionStatistics()
        {
            @Override
            public void onClosed(Connection connection)
            {
                super.onClosed(connection);
                latch.countDown();
            }
        };
        client.addManaged(clientStats);

        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertThat(clientStats.getSentBytes(), Matchers.greaterThan(0L));
        assertEquals(clientStats.getSentBytes(), serverStats.getReceivedBytes());
        assertThat(clientStats.getReceivedBytes(), Matchers.greaterThan(0L));
        assertEquals(clientStats.getReceivedBytes(), serverStats.getSentBytes());
    }
}
