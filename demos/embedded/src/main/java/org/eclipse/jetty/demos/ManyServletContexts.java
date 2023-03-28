//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.demos;

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class ManyServletContexts
{
    public static Server createServer(int port)
    {
        Server server = new Server(port);

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(
            ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer, true);

        // Declare server handler collection
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        // Configure context "/" (root) for servlets
        ServletContextHandler root = new ServletContextHandler(contexts, "/",
            ServletContextHandler.SESSIONS);
        // Add servlets to root context
        root.addServlet(new ServletHolder(new HelloServlet("Hello")), "/");
        root.addServlet(new ServletHolder(new HelloServlet("Ciao")), "/it/*");
        root.addServlet(new ServletHolder(new HelloServlet("Bonjour")), "/fr/*");

        // Configure context "/other" for servlets
        ServletContextHandler other = new ServletContextHandler(contexts,
            "/other", ServletContextHandler.SESSIONS);
        // Add servlets to /other context
        other.addServlet(DefaultServlet.class.getCanonicalName(), "/");
        other.addServlet(new ServletHolder(new HelloServlet("YO!")), "*.yo");

        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Server server = createServer(port);
        server.start();
        server.dumpStdErr();
        server.join();
    }
}
