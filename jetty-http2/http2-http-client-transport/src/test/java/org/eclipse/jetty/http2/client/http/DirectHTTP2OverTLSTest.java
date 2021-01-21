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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DirectHTTP2OverTLSTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        startServer(handler);
        startClient();
    }

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        ConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ConnectionFactory ssl = new SslConnectionFactory(newServerSslContextFactory(), h2.getProtocol());
        connector = new ServerConnector(server, 1, 1, ssl, h2);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        HttpClientTransportOverHTTP2 transport = new HttpClientTransportOverHTTP2(new HTTP2Client());
        transport.setUseALPN(false);
        SslContextFactory sslContextFactory = newClientSslContextFactory();
        client = new HttpClient(transport, sslContextFactory);
        client.setExecutor(clientThreads);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    private SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configureSslContextFactory(sslContextFactory);
        return sslContextFactory;
    }

    private SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        configureSslContextFactory(sslContextFactory);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
    }

    @Test
    public void testDirectHTTP2OverTLS() throws Exception
    {
        // The client knows a priori that the server speaks h2 on a particular port.

        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
