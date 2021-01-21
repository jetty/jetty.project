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

package org.eclipse.jetty.quickstart;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

public class Quickstart
{

    public static void main(String... args) throws Exception
    {
        if (args.length < 1)
            error("No WAR file or directory given");

        //war file or dir to start
        String war = args[0];

        //optional jetty context xml file to configure the webapp
        Resource contextXml = null;
        if (args.length > 1)
            contextXml = Resource.newResource(args[1]);

        Server server = new Server(8080);

        QuickStartWebApp webapp = new QuickStartWebApp();
        webapp.setAutoPreconfigure(true);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        if (contextXml != null)
        {
            // System.err.println("Applying "+contextXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml.getURL());
            xmlConfiguration.configure(webapp);
        }

        server.setHandler(webapp);

        server.start();

        server.join();
    }

    private static void error(String message)
    {
        System.err.println("ERROR: " + message);
        System.err.println("Usage: java -jar QuickStartWar.jar <war-directory> <context-xml>");
        System.err.println("       java -jar QuickStartWar.jar <war-file> <context-xml>");
        System.exit(1);
    }
}
