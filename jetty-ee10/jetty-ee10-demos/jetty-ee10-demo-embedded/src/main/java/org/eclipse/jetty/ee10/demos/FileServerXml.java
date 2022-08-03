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

package org.eclipse.jetty.ee10.demos;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * A Jetty FileServer.
 * <p>
 * This server is identical to {@link FileServer}, except that it is configured
 * via an {@link XmlConfiguration} config file that does the identical work.
 * </p>
 */
public class FileServerXml
{
    public static Server createServer(int port, Path baseResource) throws Exception
    {
        // Find Jetty XML (in classpath) that configures and starts Server.
        // See src/main/resources/fileserver.xml
        ResourceFactory.LifeCycle resourceFactory = ResourceFactory.lifecycle();
        Resource fileServerXml = resourceFactory.newSystemResource("fileserver.xml");
        XmlConfiguration configuration = new XmlConfiguration(fileServerXml);
        configuration.getProperties().put("http.port", Integer.toString(port));
        configuration.getProperties().put("fileserver.baseresource", baseResource.toAbsolutePath().toString());
        Server server = (Server)configuration.configure();
        server.addBean(resourceFactory, true);
        return server;
    }

    public static void main(String[] args) throws Exception
    {
        int port = ExampleUtil.getPort(args, "jetty.http.port", 8080);
        Path userDir = Paths.get(System.getProperty("user.dir"));
        Server server = createServer(port, userDir);
        server.start();
        server.join();
    }
}
