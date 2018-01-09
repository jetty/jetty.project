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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * A Jetty FileServer.
 * <p>
 * This server is identical to {@link FileServer}, except that it is configured
 * via an {@link XmlConfiguration} config file that does the identical work.
 * </p>
 * <p>
 * See <a href="https://raw.githubusercontent.com/eclipse/jetty.project/master/examples/embedded/src/main/resources/fileserver.xml">fileserver.xml</a>
 * </p>
 */
public class FileServerXml
{
    public static void main( String[] args ) throws Exception
    {
        Resource fileserverXml = Resource.newSystemResource("fileserver.xml");
        XmlConfiguration configuration = new XmlConfiguration(
                fileserverXml.getInputStream());
        Server server = (Server) configuration.configure();
        server.start();
        server.join();
    }
}
