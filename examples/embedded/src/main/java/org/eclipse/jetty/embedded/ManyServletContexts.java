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

package org.eclipse.jetty.embedded;

import java.lang.management.ManagementFactory;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class ManyServletContexts
{
    public static void main( String[] args ) throws Exception
    {
        Server server = new Server(8080);

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
        root.addServlet(new ServletHolder(new HelloServlet("Bonjoir")), "/fr/*");

        // Configure context "/other" for servlets
        ServletContextHandler other = new ServletContextHandler(contexts,
                "/other", ServletContextHandler.SESSIONS);
        // Add servlets to /other context
        other.addServlet(DefaultServlet.class.getCanonicalName(), "/");
        other.addServlet(new ServletHolder(new HelloServlet("YO!")), "*.yo");

        server.start();
        server.dumpStdErr();
        server.join();
    }
}
