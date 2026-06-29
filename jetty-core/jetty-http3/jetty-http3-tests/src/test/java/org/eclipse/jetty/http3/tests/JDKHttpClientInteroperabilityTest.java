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

package org.eclipse.jetty.http3.tests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledForJreRange(min = JRE.JAVA_26)
public class JDKHttpClientInteroperabilityTest
{
    private Server server;
    private QuicServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        ByteBufferPool byteBufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(serverThreads, null, byteBufferPool);

        SslContextFactory.Server serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12"));
        serverSslContextFactory.setKeyStorePassword("storepwd");

        connector = new QuicServerConnector(server, serverSslContextFactory, HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration()), new HTTP3ServerConnectionFactory());
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    @Test
    public void testEcho() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Content.copy(request, response, callback);
                return true;
            }
        });

        SslContextFactory.Client clientTLS = new SslContextFactory.Client();
        clientTLS.setTrustStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        clientTLS.setTrustStorePassword("storepwd");
        clientTLS.start();

        try (HttpClient jdkHttpClient = HttpClient.newBuilder().sslContext(clientTLS.getSslContext()).build())
        {
            String content = "HELLO HTTP/3";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + connector.getLocalPort()))
                .POST(HttpRequest.BodyPublishers.ofString(content))
                .version(HttpClient.Version.valueOf("HTTP_3"))
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = jdkHttpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII));
            assertEquals(200, response.statusCode());
            assertEquals(content, response.body());
        }
    }

    // TODO: test using client certificates, to verify also the interoperability when the client sends a certificate.
}
