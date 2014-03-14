//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.HashMap;
import java.util.Map;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.server.http.HTTPSPDYServerConnector;
import org.eclipse.jetty.spdy.server.http.PushStrategy;
import org.eclipse.jetty.spdy.server.http.ReferrerPushStrategy;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class WordPressSPDYFastCGIProxyServer
{
    public static void main(String[] args) throws Exception
    {
        int port = 8080;
        int tlsPort = 8443;

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setEndpointIdentificationAlgorithm("");
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");

        Server server = new Server();

        Map<Short, PushStrategy> pushStrategies = new HashMap<>();
        pushStrategies.put(SPDY.V3, new ReferrerPushStrategy());
        HTTPSPDYServerConnector tlsConnector = new HTTPSPDYServerConnector(server, sslContextFactory, pushStrategies);
        tlsConnector.setPort(tlsPort);
        server.addConnector(tlsConnector);
        HTTPSPDYServerConnector connector = new HTTPSPDYServerConnector(server, null, pushStrategies);
        connector.setPort(port);
        server.addConnector(connector);

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
