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

package org.eclipse.jetty.embedded;

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class OneServletContextJmxStats
{
    public static Server createServer(int port)
    {
        Server server = new Server(port);

        // Add JMX tracking to Server
        server.addBean(new MBeanContainer(ManagementFactory
            .getPlatformMBeanServer()));

        ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(DumpServlet.class, "/dump/*");
        context.addServlet(DefaultServlet.class, "/");

        // Add Connector Statistics tracking to all connectors
        server.addBeanToAllConnectors(new ConnectionStatistics());
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);

        server.start();
        server.join();
    }
}
