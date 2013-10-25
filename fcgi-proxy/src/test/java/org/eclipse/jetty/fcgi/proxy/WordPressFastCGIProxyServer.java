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

import java.io.IOException;
import java.util.EnumSet;
import java.util.Locale;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class WordPressFastCGIProxyServer
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        String root = "/home/simon/programs/wordpress-3.6.1";

        ServletContextHandler context = new ServletContextHandler(server, "/");
        context.setResourceBase(root);
        context.setWelcomeFiles(new String[]{"index.php"});

        // Serve static resources
        ServletHolder defaultServlet = new ServletHolder(DefaultServlet.class);
        defaultServlet.setName("default");
        context.addServlet(defaultServlet, "/");

        context.addFilter(WordPressFilter.class, "/index.php/*", EnumSet.of(DispatcherType.REQUEST));

        // FastCGI
        ServletHolder fcgiServlet = new ServletHolder(FastCGIProxyServlet.class);
        fcgiServlet.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, root);
        fcgiServlet.setInitParameter("proxyTo", "http://localhost:9000");
        fcgiServlet.setInitParameter("prefix", "/");
        fcgiServlet.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "/index\\.php(.+\\.php)");
        context.addServlet(fcgiServlet, "*.php");

        server.start();
    }

    /**
     * This filter is needed to get rid of the annoying "/index.php" prefix
     * in WordPress URLs that prevents serving correctly static resources.
     */
    public static class WordPressFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            String path = ((HttpServletRequest)request).getRequestURI().toLowerCase(Locale.ENGLISH);
            if (!path.endsWith(".php") && path.startsWith("/index.php/"))
            {
                request.getRequestDispatcher(path.substring("/index.php".length())).forward(request, response);
            }
            else
            {
                chain.doFilter(request, response);
            }
        }
        
        @Override
        public void destroy()
        {
        }
    }
}
