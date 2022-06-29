//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.test.webapp;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HTTP2FromWebAppIT
{
    @Test
    public void testHTTP2FromWebApp() throws Exception
    {
        Server server = new Server();

        SslContextFactory.Server serverTLS = new SslContextFactory.Server();
        serverTLS.setKeyStorePath("src/test/resources/keystore.p12");
        serverTLS.setKeyStorePassword("storepwd");
        serverTLS.setCipherComparator(new HTTP2Cipher.CipherComparator());

        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(serverTLS, alpn.getProtocol());
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);

        ServerConnector connector = new ServerConnector(server, ssl, alpn, h2, h1);
        server.addConnector(connector);

        String contextPath = "/http2_from_webapp";
        WebAppContext context = new WebAppContext("target/webapp", contextPath);
        server.setHandler(context);

        server.start();

        try
        {
            ClientConnector clientConnector = new ClientConnector();
            clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
            HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
            client.start();

            try
            {
                ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                    .scheme(HttpScheme.HTTPS.asString())
                    .path(contextPath + "/h1")
                    .timeout(5, TimeUnit.SECONDS)
                    .send();

                assertEquals("ok", response.getContentAsString());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            server.stop();
        }
    }
}
