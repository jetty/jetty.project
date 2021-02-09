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

import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.client.EmptyServerHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
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

        SslContextFactory clientSSL = new SslContextFactory.Client(true);
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

        SslContextFactory clientSSL = new SslContextFactory.Client(true);
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

        SslContextFactory clientSSL = new SslContextFactory.Client(true);
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

        SslContextFactory clientSSL = new SslContextFactory.Client(true);
        clientSSL.setKeyStorePath("src/test/resources/client_keystore.p12");
        clientSSL.setKeyStorePassword("storepwd");
        startClient(clientSSL);

        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(handshakeLatch.await(10, TimeUnit.SECONDS));
    }
}
