//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.ssl;

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.EmptyServerHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In order to work, client authentication needs a certificate
 * signed by a CA that also signed the server certificate.
 * <p>
 * For this test, the client certificate is signed with the server
 * certificate, and the server certificate is self-signed.
 */
public class NeedWantClientAuthTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(SslContextFactory.Server sslContextFactory, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    private void startClient(SslContextFactory.Client sslContextFactory) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSslContextFactory(sslContextFactory);
        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        client.start();
    }

    private SslContextFactory.Server createServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        return sslContextFactory;
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
    public void testWantClientAuthWithoutAuth() throws Exception
    {
        SslContextFactory.Server serverSSL = createServerSslContextFactory();
        serverSSL.setWantClientAuth(true);
        startServer(serverSSL, new EmptyServerHandler());

        SslContextFactory.Client clientSSL = new SslContextFactory.Client(true);
        startClient(clientSSL);

        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testWantClientAuthWithAuth() throws Exception
    {
        SslContextFactory.Server serverSSL = createServerSslContextFactory();
        serverSSL.setWantClientAuth(true);
        startServer(serverSSL, new EmptyServerHandler());
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                try
                {
                    SSLSession session = event.getSSLEngine().getSession();
                    Certificate[] clientCerts = session.getPeerCertificates();
                    assertNotNull(clientCerts);
                    assertThat(clientCerts.length, Matchers.greaterThan(0));
                    handshakeLatch.countDown();
                }
                catch (Throwable x)
                {
                    x.printStackTrace();
                }
            }
        });

        SslContextFactory.Client clientSSL = new SslContextFactory.Client(true);
        clientSSL.setKeyStorePath("src/test/resources/client_keystore.p12");
        clientSSL.setKeyStorePassword("storepwd");
        startClient(clientSSL);

        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(handshakeLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testNeedClientAuthWithoutAuth() throws Exception
    {
        // In TLS 1.2, the TLS handshake on the client finishes after the TLS handshake on the server.
        // The server detects the lack of the client certificate, fails its TLS handshake and sends
        // bad_certificate to the client, which then fails its own TLS handshake.
        // In TLS 1.3, the TLS handshake on the client finishes before the TLS handshake on the server.
        // The server still sends bad_certificate to the client, but the client handshake has already
        // completed successfully its TLS handshake.

        SslContextFactory.Server serverSSL = createServerSslContextFactory();
        serverSSL.setNeedClientAuth(true);
        startServer(serverSSL, new EmptyServerHandler());

        SslContextFactory.Client clientSSL = new SslContextFactory.Client(true);
        startClient(clientSSL);
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                if ("TLSv1.3".equals(event.getSSLEngine().getSession().getProtocol()))
                    handshakeLatch.countDown();
            }

            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                assertThat(failure, Matchers.instanceOf(SSLHandshakeException.class));
                handshakeLatch.countDown();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .send(result ->
            {
                if (result.isFailed())
                {
                    Throwable failure = result.getFailure();
                    if (failure instanceof SSLException)
                        latch.countDown();
                }
            });

        assertTrue(handshakeLatch.await(10, TimeUnit.SECONDS));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testNeedClientAuthWithAuth() throws Exception
    {
        SslContextFactory.Server serverSSL = createServerSslContextFactory();
        serverSSL.setNeedClientAuth(true);
        startServer(serverSSL, new EmptyServerHandler());
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                try
                {
                    SSLSession session = event.getSSLEngine().getSession();
                    Certificate[] clientCerts = session.getPeerCertificates();
                    assertNotNull(clientCerts);
                    assertThat(clientCerts.length, Matchers.greaterThan(0));
                    handshakeLatch.countDown();
                }
                catch (Throwable x)
                {
                    x.printStackTrace();
                }
            }
        });

        SslContextFactory.Client clientSSL = new SslContextFactory.Client(true);
        clientSSL.setKeyStorePath("src/test/resources/client_keystore.p12");
        clientSSL.setKeyStorePassword("storepwd");
        startClient(clientSSL);

        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(handshakeLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testTrustManagerWrapperAccessToCertChain() throws Exception
    {
        // Track certificate chain seen during validation
        AtomicReference<X509Certificate[]> seenCerts = new AtomicReference<>();

        SslContextFactory.Server serverSSL = createServerSslContextFactory();
        serverSSL.setNeedClientAuth(true);

        // Wrap TrustManager to capture certificate chain during validation
        serverSSL.setTrustManagerWrapper(delegate ->
            new SslContextFactory.X509ExtendedTrustManagerWrapper(delegate)
            {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException
                {
                    // Capture the certificate chain before validation
                    seenCerts.set(chain);
                    super.checkClientTrusted(chain, authType, engine);
                }
            });

        startServer(serverSSL, new EmptyServerHandler());

        // Client presents a certificate
        SslContextFactory.Client clientSSL = new SslContextFactory.Client(true);
        clientSSL.setKeyStorePath("src/test/resources/client_keystore.p12");
        clientSSL.setKeyStorePassword("storepwd");
        startClient(clientSSL);

        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());

        // The wrapper should have captured the client certificate chain
        assertNotNull(seenCerts.get());
        assertTrue(seenCerts.get().length > 0);
    }
}
