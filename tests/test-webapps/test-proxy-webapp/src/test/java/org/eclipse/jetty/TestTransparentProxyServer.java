//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty;

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Disabled;

@Disabled("Not a test case")
public class TestTransparentProxyServer
{
    public static void main(String[] args) throws Exception
    {
        ((StdErrLog)Log.getLog()).setSource(false);

        String jettyRoot = "../../..";

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(100);

        // Setup server
        Server server = new Server(threadPool);
        server.manage(threadPool);

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);
        server.addBean(Log.getLog());

        // Common HTTP configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSecurePort(8443);
        httpConfig.addCustomizer(new ForwardedRequestCustomizer());
        httpConfig.setSendDateHeader(true);
        httpConfig.setSendServerVersion(true);

        // Http Connector
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        ServerConnector httpConnector = new ServerConnector(server, http);
        httpConnector.setPort(8080);
        httpConnector.setIdleTimeout(30000);
        server.addConnector(httpConnector);

        // SSL configurations
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(jettyRoot + "/jetty-server/src/main/config/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        sslContextFactory.setTrustStorePath(jettyRoot + "/jetty-server/src/main/config/etc/keystore");
        sslContextFactory.setTrustStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setExcludeCipherSuites(
            "SSL_RSA_WITH_DES_CBC_SHA",
            "SSL_DHE_RSA_WITH_DES_CBC_SHA",
            "SSL_DHE_DSS_WITH_DES_CBC_SHA",
            "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
            "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
            "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");
        sslContextFactory.setCipherComparator(new HTTP2Cipher.CipherComparator());

        // HTTPS Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // HTTP2 factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h2.getProtocol());

        // SSL Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        // HTTP2 Connector
        ServerConnector http2Connector =
            new ServerConnector(server, ssl, alpn, h2, new HttpConnectionFactory(httpsConfig));
        http2Connector.setPort(8443);
        http2Connector.setIdleTimeout(15000);
        server.addConnector(http2Connector);

        // Handlers
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        handlers.setHandlers(new Handler[]
            {contexts, new DefaultHandler()});

        server.setHandler(handlers);

        // Setup proxy webapp
        WebAppContext webapp = new WebAppContext();
        webapp.setResourceBase("src/main/webapp");
        contexts.addHandler(webapp);

        // start server
        server.setStopAtShutdown(true);
        server.start();
        server.join();
    }
}
