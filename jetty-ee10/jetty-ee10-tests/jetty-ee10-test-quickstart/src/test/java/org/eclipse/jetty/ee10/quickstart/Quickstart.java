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

package org.eclipse.jetty.ee10.quickstart;

import org.eclipse.jetty.ee10.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
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
        WebAppContext webapp = new WebAppContext();
        Resource contextXml = null;
        if (args.length > 1)
            contextXml = ResourceFactory.of(webapp).newResource(args[1]);

        Server server = new Server(8080);

        webapp.addConfiguration(new QuickStartConfiguration(),
                                    new EnvConfiguration(),
                                    new PlusConfiguration(),
                                    new AnnotationConfiguration());
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.QUICKSTART);
        webapp.setWar(war);
        webapp.setContextPath("/");

        //apply context xml file
        if (contextXml != null)
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(contextXml);
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
