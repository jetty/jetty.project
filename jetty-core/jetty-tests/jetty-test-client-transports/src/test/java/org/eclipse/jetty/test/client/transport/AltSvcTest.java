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

import java.util.Locale;
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
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(WorkDirExtension.class)
// Isolated because the default Locale is changed.
@Isolated
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

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "ar-EG-u-nu-arab"})
    public void testAltSvcHeaderDoesNotDependOnDefaultLocale(String languageTag) throws Exception
    {
        server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setUseCipherSuitesOrder(true);

        // HTTP/2 connector with TLS.
        HTTP2ServerConnectionFactory h2Factory = new HTTP2ServerConnectionFactory(httpConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h2Factory.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        ServerConnector h2Connector = new ServerConnector(server, ssl, alpn, h2Factory);
        server.addConnector(h2Connector);

        // HTTP/3 connector sharing the same HttpConfiguration,
        // which adds the Alt-Svc customizer to it.
        ServerQuicConfiguration serverQuicConfig = new ServerQuicConfiguration(sslContextFactory, workDir.getEmptyPathDir());
        QuicServerConnector h3Connector = new QuicServerConnector(server, serverQuicConfig, new HTTP3ServerConnectionFactory(serverQuicConfig, httpConfig));
        server.addConnector(h3Connector);

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

        SslContextFactory.Client sslContextFactoryClient = new SslContextFactory.Client();
        sslContextFactoryClient.setTrustAll(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactoryClient);
        client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector)));
        client.start();

        // The Alt-Svc header must not depend on the default Locale
        // as the Arabic-Egyptian locale with Arabic digits does not format numbers using the ascii
        // characters 0-9 but with other UTF characters instead.
        int h2Port = h2Connector.getLocalPort();
        ContentResponse response;
        Locale defaultLocale = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            response = client.newRequest("localhost", h2Port)
                .scheme("https")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        }
        finally
        {
            Locale.setDefault(defaultLocale);
        }

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals("h3=\":" + h2Port + "\"", response.getHeaders().get(HttpHeader.ALT_SVC));
    }
}
