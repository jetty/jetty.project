//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.proxy;

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
//        int port = 8080;
        int port = 8443;

//        SslContextFactory sslContextFactory = null;
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setEndpointIdentificationAlgorithm("");
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");

        Server server = new Server();

        Map<Short, PushStrategy> pushStrategies = new HashMap<>();
        pushStrategies.put(SPDY.V3, new ReferrerPushStrategy());
        HTTPSPDYServerConnector connector = new HTTPSPDYServerConnector(server, sslContextFactory, pushStrategies);
        connector.setPort(port);
        server.addConnector(connector);

        String root = "/home/simon/programs/wordpress-3.7.1";

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.setResourceBase(root);
        context.setWelcomeFiles(new String[]{"index.php"});

        // Serve static resources
        ServletHolder defaultServlet = new ServletHolder(DefaultServlet.class);
        defaultServlet.setName("default");
        context.addServlet(defaultServlet, "/");

        FilterHolder tryFileFilter = context.addFilter(TryFilesFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        tryFileFilter.setInitParameter(TryFilesFilter.ROOT_INIT_PARAM, root);
//        tryFileFilter.setInitParameter(TryFilesFilter.FILES_INIT_PARAM, "$path $path/index.php?$query"); // Permalink /?p=123
        tryFileFilter.setInitParameter(TryFilesFilter.FILES_INIT_PARAM, "$path /index.php?p=$path&$query"); // Permalink /%year%/%monthnum%/%postname%

        // FastCGI
        ServletHolder fcgiServlet = new ServletHolder(FastCGIProxyServlet.class);
        fcgiServlet.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, root);
        fcgiServlet.setInitParameter("proxyTo", "http://localhost:9000");
        fcgiServlet.setInitParameter("prefix", "/");
        fcgiServlet.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "(.+?\\.php)");
        context.addServlet(fcgiServlet, "*.php");

        server.start();
    }
}
