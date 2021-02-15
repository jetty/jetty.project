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

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
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
        Resource fileServerXml = Resource.newSystemResource("fileserver.xml");
        XmlConfiguration configuration = new XmlConfiguration(fileServerXml);
        configuration.getProperties().put("http.port", Integer.toString(port));
        configuration.getProperties().put("fileserver.baseresource", baseResource.toAbsolutePath().toString());
        return (Server)configuration.configure();
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
