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

import java.util.Locale;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

public class PreconfigureQuickStartWar
{
    private static final Logger LOG = Log.getLogger(PreconfigureQuickStartWar.class);
    static final boolean ORIGIN = LOG.isDebugEnabled();

    public static void main(String... args) throws Exception
    {
        Resource war = null;
        Resource dir = null;
        Resource xml = null;

        switch (args.length)
        {
            case 0:
                error("No WAR file or directory given");
                break;

            case 1:
                dir = Resource.newResource(args[0]);
                break;

            case 2:
                war = Resource.newResource(args[0]);
                if (war.isDirectory())
                {
                    dir = war;
                    war = null;
                    xml = Resource.newResource(args[1]);
                }
                else
                {
                    dir = Resource.newResource(args[1]);
                }

                break;

            case 3:
                war = Resource.newResource(args[0]);
                dir = Resource.newResource(args[1]);
                xml = Resource.newResource(args[2]);
                break;

            default:
                error("Too many args");
                break;
        }

        preconfigure(war, dir, xml);
    }

    /**
     * @param war The war (or directory) to preconfigure
     * @param dir The directory to expand the war into (or null if war is a directory)
     * @param xml A context XML to apply (or null if none)
     * @throws Exception if unable to pre configure
     */
    public static void preconfigure(Resource war, Resource dir, Resource xml) throws Exception
    {
        // Do we need to unpack a war?
        if (war != null)
        {
            if (war.isDirectory())
                error("war file is directory");

            if (!dir.exists())
                dir.getFile().mkdirs();
            JarResource.newJarResource(war).copyTo(dir.getFile());
        }

        final Server server = new Server();

        QuickStartWebApp webapp = new QuickStartWebApp();

        if (xml != null)
        {
            if (xml.isDirectory() || !xml.toString().toLowerCase(Locale.ENGLISH).endsWith(".xml"))
                error("Bad context.xml: " + xml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(xml.getURL());
            xmlConfiguration.configure(webapp);
        }
        webapp.setResourceBase(dir.getFile().getAbsolutePath());
        webapp.setPreconfigure(true);
        server.setHandler(webapp);
        server.start();
        server.stop();
    }

    private static void error(String message)
    {
        System.err.println("ERROR: " + message);
        System.err.println("Usage: java -jar PreconfigureQuickStartWar.jar <war-directory>");
        System.err.println("       java -jar PreconfigureQuickStartWar.jar <war-directory> <context-xml-file>");
        System.err.println("       java -jar PreconfigureQuickStartWar.jar <war-file> <target-war-directory>");
        System.err.println("       java -jar PreconfigureQuickStartWar.jar <war-file> <target-war-directory> <context-xml-file>");
        System.exit(1);
    }
}
