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

package org.eclipse.jetty.quic.client;

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
import org.eclipse.jetty.quic.server.QuicServerConnector;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@ExtendWith(WorkDirExtension.class)
public class End2EndClientTest
{
    public WorkDir workDir;

    private Server server;
    private QuicServerConnector connector;
    private HttpClient client;
    private final String responseContent = """
        <html>
          <body>
            Request served
          </body>
        </html>
        """;

    @BeforeEach
    public void setUp() throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStore(keyStore);
        sslContextFactory.setKeyStorePassword("storepwd");

        server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory http1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        // Use the deprecated APIs for backwards compatibility testing.
        connector = new QuicServerConnector(server, sslContextFactory, http1, http2);
        connector.getQuicConfiguration().setPemWorkDirectory(workDir.getEmptyPathDir());
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

        // Use the deprecated APIs for backwards compatibility testing.
        ClientConnector clientConnector = new ClientConnector(new QuicClientConnectorConfigurator());
        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client(true);
        clientConnector.setSslContextFactory(clientSslContextFactory);
        ClientConnectionFactory.Info http1Info = HttpClientConnectionFactory.HTTP11;
        ClientConnectionFactoryOverHTTP2.HTTP2 http2Info = new ClientConnectionFactoryOverHTTP2.HTTP2(new HTTP2Client(clientConnector));
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(clientConnector, http1Info, http2Info);
        client = new HttpClient(transport);
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    @Tag("flaky") // Issue #8815
    public void testSimpleHTTP1() throws Exception
    {
        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
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
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertThat(response.getStatus(), is(200));
            String contentAsString = response.getContentAsString();
            assertThat(contentAsString, is(responseContent));
        }
    }

    @Test
    @Tag("flaky") // Issue #8815
    public void testMultiThreadedHTTP1()
    {
        int count = 1000;
        CompletableFuture<?>[] futures = new CompletableFuture[count];
        for (int i = 0; i < count; ++i)
        {
            String path = "/" + i;
            futures[i] = CompletableFuture.runAsync(() ->
            {
                try
                {
                    ContentResponse response = client.GET("https://localhost:" + connector.getLocalPort() + path);
                    assertThat(response.getStatus(), is(200));
                    String contentAsString = response.getContentAsString();
                    assertThat(contentAsString, is(responseContent));
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            });
        }
        CompletableFuture.allOf(futures)
            .orTimeout(15, TimeUnit.SECONDS)
            .join();
    }
}
