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

package org.eclipse.jetty.quic.tests;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.quic.quiche.client.QuicheClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.client.QuicheTransport;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(WorkDirExtension.class)
public class HTTP2OverQuicTest extends AbstractTest
{
    public WorkDir workDir;
    private Server server;
    private NetworkConnector connector;
    private HttpClient httpClient;
    private Transport transport;

    protected void start(TransportType transportType, Handler handler) throws Exception
    {
        server = new Server();

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("server_keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        httpConfig.addCustomizer(new HostHeaderCustomizer());

        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfig);

        connector = switch (transportType)
        {
            case QUICHE ->
            {
                QuicheServerQuicConfiguration serverQuicConfig = new QuicheServerQuicConfiguration(workDir.getEmptyPathDir());
                yield new QuicheServerConnector(server, sslContextFactory, serverQuicConfig, h2);
            }
            case QUIC ->
            {
                QuicServerQuicConfiguration serverQuicConfig = new QuicServerQuicConfiguration();
                yield new QuicServerConnector(server, sslContextFactory, serverQuicConfig, h2);
            }
        };
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        http2Client.setUseALPN(true);
        http2Client.setApplicationProtocols(List.of("h2"));
        httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        httpClient.start();

        transport = switch (transportType)
        {
            case QUICHE -> new QuicheTransport(new QuicheClientQuicConfiguration());
            case QUIC ->
            {
                QuicClient quicClient = new QuicClient(new QuicClientQuicConfiguration());
                quicClient.start();
                yield new QuicTransport(quicClient);
            }
        };
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(httpClient);
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHTTP2(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                return true;
            }
        });

        String content = "Hello QUIC";
        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .transport(transport)
            .body(new StringRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
    }
}
