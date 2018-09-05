//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

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

    private SslContextFactory createSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setEndpointIdentificationAlgorithm("");
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        return sslContextFactory;
    }

    @After
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
        SslContextFactory serverTLSFactory = createSslContextFactory();
        serverTLSFactory.setIncludeProtocols("TLSv1.2");
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

        SslContextFactory clientTLSFactory = createSslContextFactory();
        clientTLSFactory.setIncludeProtocols("TLSv1.1");
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }

        Assert.assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testNoCommonTLSCiphers() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
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

        SslContextFactory clientTLSFactory = createSslContextFactory();
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }

        Assert.assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnServer() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
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

        SslContextFactory clientTLSFactory = createSslContextFactory();
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }

        Assert.assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnClient() throws Exception
    {
        // In JDK 11, a mismatch on the client does not generate any bytes towards
        // the server, while in TLS 1.2 the client sends to the server the close_notify.
        Assume.assumeThat(JavaVersion.VERSION.getPlatform(), Matchers.lessThan(11));

        SslContextFactory serverTLSFactory = createSslContextFactory();
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

        SslContextFactory clientTLSFactory = createSslContextFactory();
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

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            // Expected.
        }

        Assert.assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandshakeSucceeded() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
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

        SslContextFactory clientTLSFactory = createSslContextFactory();
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
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Assert.assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandshakeSucceededWithSessionResumption() throws Exception
    {
        // Excluded because of a bug in JDK 11+27 where session resumption does not work.
        Assume.assumeThat(JavaVersion.VERSION.getPlatform(), Matchers.lessThan(11));

        SslContextFactory serverTLSFactory = createSslContextFactory();
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

        SslContextFactory clientTLSFactory = createSslContextFactory();
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
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Assert.assertNotNull(serverSession.get());
        Assert.assertNotNull(clientSession.get());

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
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Assert.assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testClientRawCloseDoesNotInvalidateSession() throws Exception
    {
        // Excluded because of a bug in JDK 11+27 where session resumption does not work.
        Assume.assumeThat(JavaVersion.VERSION.getPlatform(), Matchers.lessThan(11));

        SslContextFactory serverTLSFactory = createSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createSslContextFactory();
        clientTLSFactory.start();

        String host = "localhost";
        int port = connector.getLocalPort();
        Socket socket = new Socket(host, port);
        SSLSocket sslSocket = (SSLSocket)clientTLSFactory.getSslContext().getSocketFactory().createSocket(socket, host, port, true);
        CountDownLatch handshakeLatch1 = new CountDownLatch(1);
        AtomicReference<byte[]> session1 = new AtomicReference<>();
        sslSocket.addHandshakeCompletedListener(event ->
        {
            session1.set(event.getSession().getId());
            handshakeLatch1.countDown();
        });
        sslSocket.startHandshake();
        Assert.assertTrue(handshakeLatch1.await(5, TimeUnit.SECONDS));

        // In TLS 1.3 the server sends a NewSessionTicket post-handshake message
        // to enable session resumption and without a read, the message is not processed.
        try
        {
            sslSocket.setSoTimeout(1000);
            sslSocket.getInputStream().read();
        }
        catch (SocketTimeoutException expected)
        {
        }

        // The client closes abruptly.
        socket.close();

        // Try again and compare the session ids.
        socket = new Socket(host, port);
        sslSocket = (SSLSocket)clientTLSFactory.getSslContext().getSocketFactory().createSocket(socket, host, port, true);
        CountDownLatch handshakeLatch2 = new CountDownLatch(1);
        AtomicReference<byte[]> session2 = new AtomicReference<>();
        sslSocket.addHandshakeCompletedListener(event ->
        {
            session2.set(event.getSession().getId());
            handshakeLatch2.countDown();
        });
        sslSocket.startHandshake();
        Assert.assertTrue(handshakeLatch2.await(5, TimeUnit.SECONDS));

        Assert.assertArrayEquals(session1.get(), session2.get());

        sslSocket.close();
    }

    @Test
    public void testServerRawCloseDetectedByClient() throws Exception
    {
        SslContextFactory serverTLSFactory = createSslContextFactory();
        serverTLSFactory.start();
        try (ServerSocket server = new ServerSocket(0))
        {
            QueuedThreadPool clientThreads = new QueuedThreadPool();
            clientThreads.setName("client");
            client = new HttpClient(createSslContextFactory())
            {
                @Override
                protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
                {
                    SslClientConnectionFactory ssl = (SslClientConnectionFactory)super.newSslClientConnectionFactory(connectionFactory);
                    ssl.setAllowMissingCloseMessage(false);
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
                        Assert.assertThat(result.getResponseFailure(), Matchers.instanceOf(SSLException.class));
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
                    if (line == null || line.isEmpty())
                        break;
                }

                // If the response is Content-Length delimited, allowing the
                // missing TLS Close Message is fine because the application
                // will see a EOFException anyway.
                // If the response is connection delimited, allowing the
                // missing TLS Close Message is bad because the application
                // will see a successful response with truncated content.

                // Verify that by not allowing the missing
                // TLS Close Message we get a response failure.

                byte[] half = new byte[8];
                String response = "HTTP/1.1 200 OK\r\n" +
//                        "Content-Length: " + (half.length * 2) + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
                OutputStream output = sslSocket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.write(half);
                output.flush();
                // Simulate a truncation attack by raw closing
                // the socket in the try-with-resources block end.
            }

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }
}
