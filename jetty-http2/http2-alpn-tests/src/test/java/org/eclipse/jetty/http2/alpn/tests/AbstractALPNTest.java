//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.alpn.tests;

import java.net.InetSocketAddress;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

// The mandatory cipher needed to run HTTP/2
// over TLS is only available in JDK 8.
@EnabledOnJre(JRE.JAVA_8)
public class AbstractALPNTest
{
    protected Server server;
    protected ServerConnector connector;

    protected InetSocketAddress prepare() throws Exception
    {
        server = new Server();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());

        connector = new ServerConnector(server, newServerSslContextFactory(), alpn, h1, h2);
        connector.setPort(0);
        connector.setIdleTimeout(30000);
        server.addConnector(connector);
        server.start();

        ALPN.debug = true;

        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server result = new SslContextFactory.Server();
        configureSslContextFactory(result);
        return result;
    }

    protected SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client result = new SslContextFactory.Client();
        configureSslContextFactory(result);
        return result;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setIncludeProtocols("TLSv1.2");
        // The mandatory HTTP/2 cipher.
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }
}
