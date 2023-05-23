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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class End2EndClientWithClientCertAuthTest
{
    public WorkDir workDir;

    private Server server;
    private QuicServerConnector connector;
    private HttpClient client;
    private final String responseContent = "" +
        "<html>\n" +
        "\t<body>\n" +
        "\t\tRequest served\n" +
        "\t</body>\n" +
        "</html>";
    private SslContextFactory.Server serverSslContextFactory;

    @BeforeEach
    public void setUp() throws Exception
    {
        Path workPath = workDir.getEmptyPathDir();
        Path serverWorkPath = workPath.resolve("server");
        Files.createDirectories(serverWorkPath);
        Path clientWorkPath = workPath.resolve("client");
        Files.createDirectories(clientWorkPath);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = getClass().getResourceAsStream("/keystore.p12"))
        {
            keyStore.load(is, "storepwd".toCharArray());
        }

        serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStore(keyStore);
        serverSslContextFactory.setKeyStorePassword("storepwd");
        serverSslContextFactory.setTrustStore(keyStore);
        serverSslContextFactory.setNeedClientAuth(true);

        server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory http1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        connector = new QuicServerConnector(server, serverSslContextFactory, http1, http2);
        connector.getQuicConfiguration().setPemWorkDirectory(serverWorkPath);
        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                PrintWriter writer = response.getWriter();
                writer.print(responseContent);
            }
        });

        server.start();

        QuicClientConnectorConfigurator configurator = new QuicClientConnectorConfigurator();
        configurator.getQuicConfiguration().setPemWorkDirectory(clientWorkPath);
        ClientConnector clientConnector = new ClientConnector(configurator);
        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client();
        clientSslContextFactory.setCertAlias("mykey");
        clientSslContextFactory.setKeyStore(keyStore);
        clientSslContextFactory.setKeyStorePassword("storepwd");
        clientSslContextFactory.setTrustStore(keyStore);
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
    public void testWorkingClientAuth() throws Exception
    {
        ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertThat(response.getStatus(), is(200));
        String contentAsString = response.getContentAsString();
        assertThat(contentAsString, is(responseContent));
    }

    @Test
    public void testServerRejectsClientInvalidCert() throws Exception
    {
        // remove the trust store config from the server
        server.stop();
        serverSslContextFactory.setTrustStore(null);
        server.start();

        assertThrows(TimeoutException.class, () ->
        {
            ContentResponse response = client.newRequest("https://localhost:" + connector.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
    }
}
