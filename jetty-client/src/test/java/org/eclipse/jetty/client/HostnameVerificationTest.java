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

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLHandshakeException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test class runs tests to make sure that hostname verification (http://www.ietf.org/rfc/rfc2818.txt
 * section 3.1) is configurable in SslContextFactory and works as expected.
 */
public class HostnameVerificationTest
{
    private SslContextFactory clientSslContextFactory = new SslContextFactory.Client();
    private Server server;
    private HttpClient client;
    private NetworkConnector connector;

    @BeforeEach
    public void setUp() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        SslContextFactory serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        serverSslContextFactory.setKeyStorePassword("storepwd");
        connector = new ServerConnector(server, serverSslContextFactory);
        server.addConnector(connector);
        server.setHandler(new DefaultHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getWriter().write("foobar");
            }
        });
        server.start();

        // keystore contains a hostname which doesn't match localhost
        clientSslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        clientSslContextFactory.setKeyStorePassword("storepwd");

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(clientSslContextFactory);
        client.setExecutor(clientThreads);
        client.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        client.stop();
        server.stop();
    }

    /**
     * This test is supposed to verify that hostname verification works as described in:
     * http://www.ietf.org/rfc/rfc2818.txt section 3.1. It uses a certificate with a common name different to localhost
     * and sends a request to localhost. This should fail with an SSLHandshakeException.
     */
    @Test
    public void simpleGetWithHostnameVerificationEnabledTest()
    {
        clientSslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        String uri = "https://localhost:" + connector.getLocalPort() + "/";

        ExecutionException x = assertThrows(ExecutionException.class, () -> client.GET(uri));

        Throwable cause = x.getCause();
        assertThat(cause, Matchers.instanceOf(SSLHandshakeException.class));

        // Search for the CertificateException.
        Throwable certificateException = cause.getCause();
        while (certificateException != null)
        {
            if (certificateException instanceof CertificateException)
                break;
            certificateException = certificateException.getCause();
        }
        assertThat(certificateException, Matchers.instanceOf(CertificateException.class));
    }

    /**
     * This test has hostname verification disabled and connecting, ssl handshake and sending the request should just
     * work fine.
     *
     * @throws Exception on test failure
     */
    @Test
    public void simpleGetWithHostnameVerificationDisabledTest() throws Exception
    {
        clientSslContextFactory.setEndpointIdentificationAlgorithm(null);
        String uri = "https://localhost:" + connector.getLocalPort() + "/";
        try
        {
            client.GET(uri);
        }
        catch (ExecutionException e)
        {
            fail("SSLHandshake should work just fine as hostname verification is disabled!", e);
        }
    }

    /**
     * This test has hostname verification disabled by setting trustAll to true and connecting,
     * ssl handshake and sending the request should just work fine.
     *
     * @throws Exception on test failure
     */
    @Test
    public void trustAllDisablesHostnameVerificationTest() throws Exception
    {
        clientSslContextFactory.setTrustAll(true);
        String uri = "https://localhost:" + connector.getLocalPort() + "/";
        try
        {
            client.GET(uri);
        }
        catch (ExecutionException e)
        {
            fail("SSLHandshake should work just fine as hostname verification is disabled!", e);
        }
    }
}
