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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Configures and Starts a Jetty server from an XML declaration.
 */
public class ExampleServerXml
{
    public static Server createServer(int port) throws Exception
    {
        // Find Jetty XML (in classpath) that configures and starts Server.
        // See src/main/resources/exampleserver.xml
        ResourceFactory.LifeCycle resourceFactory = ResourceFactory.lifecycle();
        Resource serverXml = resourceFactory.newSystemResource("exampleserver.xml");
        XmlConfiguration xml = new XmlConfiguration(serverXml);
        xml.getProperties().put("http.port", Integer.toString(port));
        Server server = (Server)xml.configure();
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
