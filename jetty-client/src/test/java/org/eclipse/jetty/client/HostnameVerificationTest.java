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

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLHandshakeException;
import javax.servlet.ServletException;
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
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * This test class runs tests to make sure that hostname verification (http://www.ietf.org/rfc/rfc2818.txt
 * section 3.1) is configurable in SslContextFactory and works as expected.
 */
public class HostnameVerificationTest
{
    private SslContextFactory clientSslContextFactory = new SslContextFactory();
    private Server server = new Server();
    private HttpClient client;
    private NetworkConnector connector;

    @Before
    public void setUp() throws Exception
    {
        SslContextFactory serverSslContextFactory = new SslContextFactory();
        serverSslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        serverSslContextFactory.setKeyStorePassword("storepwd");
        connector = new ServerConnector(server, serverSslContextFactory);
        server.addConnector(connector);
        server.setHandler(new DefaultHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.getWriter().write("foobar");
            }
        });
        server.start();

        // keystore contains a hostname which doesn't match localhost
        clientSslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        clientSslContextFactory.setKeyStorePassword("storepwd");

        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName(executor.getName() + "-client");
        client = new HttpClient(clientSslContextFactory);
        client.setExecutor(executor);
        client.start();
    }

    @After
    public void tearDown() throws Exception
    {
        client.stop();
        server.stop();
        server.join();
    }

    /**
     * This test is supposed to verify that hostname verification works as described in:
     * http://www.ietf.org/rfc/rfc2818.txt section 3.1. It uses a certificate with a common name different to localhost
     * and sends a request to localhost. This should fail with a SSLHandshakeException.
     *
     * @throws Exception on test failure
     */
    @Test
    public void simpleGetWithHostnameVerificationEnabledTest() throws Exception
    {
        clientSslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        String uri = "https://localhost:" + connector.getLocalPort() + "/";
        try
        {
            client.GET(uri);
            Assert.fail("sending request to client should have failed with an Exception!");
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            Assert.assertThat(cause, Matchers.instanceOf(SSLHandshakeException.class));
            Throwable root = cause.getCause().getCause();
            Assert.assertThat(root, Matchers.instanceOf(CertificateException.class));
        }
    }

    /**
     * This test has hostname verification disabled and connecting, ssl handshake and sending the request should just
     * work fine.
     *
     * @throws Exception on test failure
     *
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
            Assert.fail("SSLHandshake should work just fine as hostname verification is disabled! " + e.getMessage());
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
            Assert.fail("SSLHandshake should work just fine as hostname verification is disabled! " + e.getMessage());
        }
    }
}
