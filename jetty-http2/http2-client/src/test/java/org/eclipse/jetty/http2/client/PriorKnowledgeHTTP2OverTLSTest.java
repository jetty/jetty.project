//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PriorKnowledgeHTTP2OverTLSTest
{
    private Server server;
    private ServerConnector connector;
    private HTTP2Client client;

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
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        client = new HTTP2Client(clientConnector);
        client.setUseALPN(false);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
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
//        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
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
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
            }
        });

        int port = connector.getLocalPort();
        MetaData.Response response = client.connect(client.getClientConnector().getSslContextFactory(), new InetSocketAddress("localhost", port), new Session.Listener.Adapter())
            .thenCompose(session ->
            {
                CompletableFuture<MetaData.Response> responsePromise = new CompletableFuture<>();
                HttpURI.Mutable uri = HttpURI.build("https://localhost:" + port + "/path");
                MetaData.Request request = new MetaData.Request("GET", uri, HttpVersion.HTTP_2, HttpFields.EMPTY);
                return session.newStream(new HeadersFrame(request, null, true), new Stream.Listener.Adapter()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        assertTrue(frame.isEndStream());
                        MetaData metaData = frame.getMetaData();
                        assertTrue(metaData.isResponse());
                        MetaData.Response response = (MetaData.Response)metaData;
                        responsePromise.complete(response);
                    }
                }).thenCompose(stream -> responsePromise);
            })
            .get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
