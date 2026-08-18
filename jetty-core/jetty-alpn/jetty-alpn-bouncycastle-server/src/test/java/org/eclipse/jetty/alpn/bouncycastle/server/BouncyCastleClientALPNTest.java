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

package org.eclipse.jetty.alpn.bouncycastle.server;

import java.nio.file.Path;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BouncyCastleClientALPNTest
{
    static
    {
        // Required to provide a SecureRandom with name "DEFAULT" used by the BC JSSE provider.
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);
    }

    private final Server server = new Server();

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testHTTP11FallbackWithoutNegotiatedProtocol() throws Exception
    {
        HttpConnectionFactory http = new HttpConnectionFactory();
        SslConnectionFactory ssl = new SslConnectionFactory(newServerSslContextFactory(), http.getProtocol());
        ServerConnector connector = new ServerConnector(server, ssl, http);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        server.start();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(
            clientConnector,
            HttpClientConnectionFactory.HTTP11,
            new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client));

        try (HttpClient client = new HttpClient(transport))
        {
            client.start();
            ContentResponse response = client.GET("https://localhost:" + connector.getLocalPort());
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals(HttpVersion.HTTP_1_1, response.getVersion());
        }
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
        Path ksPath = MavenPaths.findTestResourceFile("keystore.p12");
        sslContextFactory.setKeyStorePath(ksPath);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setProvider(BouncyCastleJsseProvider.PROVIDER_NAME);
    }
}
