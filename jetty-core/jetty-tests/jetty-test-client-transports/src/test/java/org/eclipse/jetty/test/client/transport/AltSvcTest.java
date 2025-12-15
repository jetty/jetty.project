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

package org.eclipse.jetty.test.client.transport;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class AltSvcTest
{
    public WorkDir workDir;
    private Server server;
    private HttpClient client;

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testAltSvcHeaderContainsHTTP3Port() throws Exception
    {
        // Setup server with HTTP/2 (TLS) and HTTP/3 on different ports
        server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        // SSL context factory for server
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setUseCipherSuitesOrder(true);

        // HTTP/2 connector with TLS
        HTTP2ServerConnectionFactory h2Factory = new HTTP2ServerConnectionFactory(httpConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h2Factory.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        ServerConnector h2Connector = new ServerConnector(server, ssl, alpn, h2Factory);
        h2Connector.setPort(0);
        server.addConnector(h2Connector);

        // HTTP/3 connector on a different port
        QuicheServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicheServerQuicConfiguration(workDir.getEmptyPathDir()));
        HTTP3ServerConnectionFactory h3Factory = new HTTP3ServerConnectionFactory(httpConfig);
        QuicheServerConnector h3Connector = new QuicheServerConnector(server, sslContextFactory, serverQuicConfig, h3Factory);
        h3Connector.setPort(0);
        server.addConnector(h3Connector);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        });

        server.start();

        int h2Port = h2Connector.getLocalPort();
        int h3Port = h3Connector.getLocalPort();

        // Verify ports are different for this test
        assertNotEquals(h2Port, h3Port, "Test requires different ports for HTTP/2 and HTTP/3");

        // Create HTTP/2 client with TLS
        SslContextFactory.Client sslContextFactoryClient = new SslContextFactory.Client();
        sslContextFactoryClient.setTrustAll(true);

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactoryClient);

        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        client = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        client.start();

        // Make HTTP/2 request
        ContentResponse response = client.newRequest("localhost", h2Port)
            .scheme("https")
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Verify Alt-Svc header contains the HTTP/3 port, not the HTTP/2 port
        String altSvc = response.getHeaders().get(HttpHeader.ALT_SVC);
        assertNotNull(altSvc, "Alt-Svc header should be present");
        assertTrue(altSvc.contains(String.format("h3=\":%d\"", h3Port)),
            "Alt-Svc header should contain HTTP/3 port " + h3Port + ", but was: " + altSvc);
    }
}
