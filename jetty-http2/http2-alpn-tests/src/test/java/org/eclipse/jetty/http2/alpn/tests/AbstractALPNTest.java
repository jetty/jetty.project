//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;

public class AbstractALPNTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();
    protected Server server;
    protected ServerConnector connector;

    @Before
    public void before()
    {
        assumeJavaVersionSupportsALPN();
    }

    protected void assumeJavaVersionSupportsALPN()
    {
        boolean isALPNSupported = false;

        if (JavaVersion.VERSION.getPlatform() >= 9)
        {
            // Java 9+ is always supported with the native java ALPN support libs
            isALPNSupported = true;
        }
        else
        {
            // Java 8 updates around update 252 are not supported in Jetty 9.3 (it requires a new ALPN support library that exists only in Java 9.4+)
            try
            {
                // JDK 8u252 has the JDK 9 ALPN API backported.
                SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
                SSLEngine.class.getMethod("getApplicationProtocol");
                // This means we have a new version of Java 8 that has ALPN backported, which Jetty 9.3 does not support.
                // Use Jetty 9.4 for proper support.
                isALPNSupported = false;
            }
            catch (NoSuchMethodException x)
            {
                // this means we have an old version of Java 8 that needs the XBootclasspath support libs
                isALPNSupported = true;
            }
        }

        Assume.assumeTrue("ALPN support exists", isALPNSupported);
    }

    protected InetSocketAddress prepare() throws Exception
    {
        server = new Server();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        
        connector = new ServerConnector(server, newSslContextFactory(), alpn, h1, h2);
        connector.setPort(0);
        connector.setIdleTimeout(30000);
        server.addConnector(connector);
        server.start();

        ALPN.debug = true;

        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected SslContextFactory newSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setIncludeProtocols("TLSv1.2");
        // The mandatory HTTP/2 cipher.
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        return sslContextFactory;
    }

    @After
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }
}
