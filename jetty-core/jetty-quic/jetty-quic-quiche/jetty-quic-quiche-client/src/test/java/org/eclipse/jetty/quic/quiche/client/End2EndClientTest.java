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

package org.eclipse.jetty.quic.quiche.client;

import java.io.InputStream;
import java.security.KeyStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@ExtendWith(WorkDirExtension.class)
public class End2EndClientTest
{
    public WorkDir workDir;

    private Server server;
    private QuicheServerConnector connector;
    private HttpClient client;
    private final String responseContent = """
        <html>
          <body>
            Request served
          </body>
        </html>
        """;
    private QuicheTransport transport;

    @BeforeEach
    public void setUp() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStore(keyStore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory http1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        QuicheServerQuicConfiguration quicConfiguration = new QuicheServerQuicConfiguration(null);
        connector = new QuicheServerConnector(server, sslContextFactory, quicConfiguration, http1, http2);
        connector.getServerQuicConfiguration().setPemWorkDirectory(workDir.getEmptyPathDir());
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, responseContent, callback);
                return true;
            }
        });

        server.start();

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientThreads.setDetailedDump(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setExecutor(clientThreads);
        transport = new QuicheTransport(new QuicheClientQuicConfiguration());
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        ClientConnectionFactory.Info http1Info = HttpClientConnectionFactory.HTTP11;
        ClientConnectionFactoryOverHTTP2.HTTP2 http2Info = new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector));
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, http1Info, http2Info));
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testSimpleHTTP1() throws Exception
    {
        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .transport(transport)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        String contentAsString = response.getContentAsString();
        assertThat(contentAsString, is(responseContent));
    }

    @Test
    public void testSimpleHTTP2() throws Exception
    {
        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .version(HttpVersion.HTTP_2)
            .transport(transport)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        String contentAsString = response.getContentAsString();
        assertThat(contentAsString, is(responseContent));
    }

    @Test
    public void testManyHTTP1() throws Exception
    {
        for (int i = 0; i < 1000; i++)
        {
            ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort() + "/" + i)
                .transport(transport)
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), is(200));
            String contentAsString = response.getContentAsString();
            assertThat(contentAsString, is(responseContent));
        }
    }

    @Test
    public void testMultiThreadedHTTP1() throws Exception
    {
        // Quiche is very slow at closing connections.
        client.setMaxConnectionsPerDestination(4);

        int count = ((ThreadPool.SizedThreadPool)client.getExecutor()).getMaxThreads() / 2;
        CompletableFuture<?>[] futures = new CompletableFuture[count];
        for (int i = 0; i < count; ++i)
        {
            String path = "/" + i;
            futures[i] = CompletableFuture.runAsync(() ->
            {
                try
                {
                    ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort() + path)
                        .transport(transport)
                        .send();
                    assertThat(response.getStatus(), is(200));
                    String contentAsString = response.getContentAsString();
                    assertThat(contentAsString, is(responseContent));
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }, client.getExecutor());
        }
        CompletableFuture.allOf(futures)
            .orTimeout(15, TimeUnit.SECONDS)
            .join();
    }
}
