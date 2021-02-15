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

package org.eclipse.jetty.fcgi.server.proxy;

import java.util.EnumSet;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class WordPressHTTP2FastCGIProxyServer
{
    public static void main(String[] args) throws Exception
    {
        int tlsPort = 8443;

        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setCipherComparator(new HTTP2Cipher.CipherComparator());

        Server server = new Server();

        // HTTP(S) Configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
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
        http2Connector.setPort(tlsPort);
        http2Connector.setIdleTimeout(15000);
        server.addConnector(http2Connector);

        String root = "/home/simon/programs/wordpress-3.7.1";

        ServletContextHandler context = new ServletContextHandler(server, "/wp");
        context.setResourceBase(root);
        context.setWelcomeFiles(new String[]{"index.php"});

        // Serve static resources
        ServletHolder defaultServlet = new ServletHolder("default", DefaultServlet.class);
        context.addServlet(defaultServlet, "/");

        FilterHolder tryFilesFilter = context.addFilter(TryFilesFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
//        tryFilesFilter.setInitParameter(TryFilesFilter.FILES_INIT_PARAM, "$path $path/index.php"); // Permalink /?p=123
        tryFilesFilter.setInitParameter(TryFilesFilter.FILES_INIT_PARAM, "$path /index.php?p=$path"); // Permalink /%year%/%monthnum%/%postname%

        // FastCGI
        ServletHolder fcgiServlet = context.addServlet(FastCGIProxyServlet.class, "*.php");
        fcgiServlet.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, root);
        fcgiServlet.setInitParameter("proxyTo", "http://localhost:9000");
        fcgiServlet.setInitParameter("prefix", "/");
        fcgiServlet.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "(.+?\\.php)");

        server.start();
    }
}
