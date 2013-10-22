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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class FastCGIProxyServer
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        ServletHolder servletHolder = new ServletHolder(FastCGIProxyServlet.class);
        servletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, "/var/www/php-fcgi");
        servletHolder.setInitParameter("proxyTo", "http://localhost:9000/");
        servletHolder.setInitParameter("prefix", "/");
        context.addServlet(servletHolder, "/*");

        server.start();
    }
}
