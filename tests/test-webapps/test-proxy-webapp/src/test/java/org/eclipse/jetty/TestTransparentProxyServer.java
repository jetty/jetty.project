//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import org.junit.Ignore;

@Ignore("Not a test case")
public class TestTransparentProxyServer
{
    public static void main(String[] args) throws Exception
    {
        ((StdErrLog)Log.getLog()).setSource(false);

        String jetty_root = "../../..";

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(100);

        // Setup server
        Server server = new Server(threadPool);
        server.manage(threadPool);

        // Setup JMX
        MBeanContainer mbContainer=new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);
        server.addBean(Log.getLog());
        

        // Common HTTP configuration
        HttpConfiguration config = new HttpConfiguration();
        config.setSecurePort(8443);
        config.addCustomizer(new ForwardedRequestCustomizer());
        config.setSendDateHeader(true);
        config.setSendServerVersion(true);
        
        
        // Http Connector
        HttpConnectionFactory http = new HttpConnectionFactory(config);
        ServerConnector httpConnector = new ServerConnector(server,http);
        httpConnector.setPort(8080);
        httpConnector.setIdleTimeout(30000);
        server.addConnector(httpConnector);

        
        // SSL configurations
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(jetty_root + "/jetty-server/src/main/config/etc/keystore");
        sslContextFactory.setKeyStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
        sslContextFactory.setKeyManagerPassword("OBF:1u2u1wml1z7s1z7a1wnl1u2g");
        sslContextFactory.setTrustStorePath(jetty_root + "/jetty-server/src/main/config/etc/keystore");
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
        HttpConfiguration https_config = new HttpConfiguration(config);
        https_config.addCustomizer(new SecureRequestCustomizer());
        
        // HTTP2 factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(https_config);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h2.getProtocol());
        
        // SSL Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory,alpn.getProtocol());
        
        // HTTP2 Connector
        ServerConnector http2Connector = 
            new ServerConnector(server,ssl,alpn,h2,new HttpConnectionFactory(https_config));
        http2Connector.setPort(8443);
        http2Connector.setIdleTimeout(15000);
        server.addConnector(http2Connector);
        
        // Handlers
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        handlers.setHandlers(new Handler[]
        { contexts, new DefaultHandler() });

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
