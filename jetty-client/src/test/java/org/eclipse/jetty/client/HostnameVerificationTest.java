//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import org.junit.Before;
import org.junit.Test;

import static junit.framework.Assert.fail;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * This test class runs tests to make sure that hostname verification (http://www.ietf.org/rfc/rfc2818.txt section 3
 * .1) is configurable in SslContextFactory and works as expected.
 */
public class HostnameVerificationTest
{
    private SslContextFactory sslContextFactory = new SslContextFactory();
    private Server server;
    private HttpClient client;
    private NetworkConnector connector;

    @Before
    public void setUp() throws Exception
    {
        if (sslContextFactory != null)
        {
            // keystore contains a hostname which doesn't match localhost
            sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
            sslContextFactory.setKeyStorePassword("storepwd");
        }

        if (server == null)
            server = new Server();
        connector = new ServerConnector(server, sslContextFactory);
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

        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName(executor.getName() + "-client");
        client = new HttpClient(sslContextFactory);
        client.setExecutor(executor);
        client.start();
    }

    /**
     * This test is supposed to verify that hostname verification works as described in:
     * http://www.ietf.org/rfc/rfc2818.txt section 3.1. It uses a certificate with a common name different to localhost
     * and sends a request to localhost. This should fail with a SSLHandshakeException.
     *
     * @throws Exception
     */
    @Test
    public void simpleGetWithHostnameVerificationEnabledTest() throws Exception
    {
        String uri = "https://localhost:" + connector.getLocalPort() + "/";
        try
        {
            client.GET(uri);
            fail("sending request to client should have failed with an Exception!");
        }
        catch (ExecutionException e)
        {
            assertThat("We got a SSLHandshakeException as localhost doesn't match the hostname of the certificate",
                    e.getCause().getCause(), instanceOf(SSLHandshakeException.class));
        }
    }

    /**
     * This test has hostname verification disabled and connecting, ssl handshake and sending the request should just
     * work fine.
     *
     * @throws Exception
     */
    @Test
    public void simpleGetWithHostnameVerificationDisabledTest() throws Exception
    {
        sslContextFactory.setEndpointIdentificationAlgorithm("");
        String uri = "https://localhost:" + connector.getLocalPort() + "/";
        try
        {
            client.GET(uri);
        }
        catch (ExecutionException e)
        {
            fail("SSLHandshake should work just fine as hostname verification is disabled! " + e.getMessage());
        }
    }
}
