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

import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;

/**
 * The simplest possible Jetty server.
 */
public class ServerWithJMX
{
    public static void main( String[] args ) throws Exception
    {
        // === jetty-jmx.xml ===
        MBeanContainer mbContainer = new MBeanContainer(
                ManagementFactory.getPlatformMBeanServer());
        
        Server server = new Server(8080);
        server.addBean(mbContainer);
        
        ConnectorServer jmx = new ConnectorServer(
                new JMXServiceURL(
                        "rmi",
                        null,
                        1999,
                        "/jndi/rmi://localhost:1999/jmxrmi"),
                        "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        server.addBean(jmx);
        
        server.start();
        server.dumpStdErr();
        server.join();
    }
}
