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
import java.net.MalformedURLException;
import javax.management.remote.JMXServiceURL;

import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;

/**
 * A Jetty Server with JMX enabled for remote connections
 */
public class ServerWithJMX
{
    public static Server createServer(int port) throws MalformedURLException
    {
        Server server = new Server(port);

        MBeanContainer mbContainer = new MBeanContainer(
            ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);

        ConnectorServer jmx = new ConnectorServer(
            new JMXServiceURL(
                "rmi",
                null,
                1999,
                "/jndi/rmi://localhost:1999/jmxrmi"),
            "org.eclipse.jetty.jmx:name=rmiconnectorserver");
        server.addBean(jmx);

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
