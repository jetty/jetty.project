//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientTLSTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(SslContextFactory sslContextFactory, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
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
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
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
}
