//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.demos;

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
