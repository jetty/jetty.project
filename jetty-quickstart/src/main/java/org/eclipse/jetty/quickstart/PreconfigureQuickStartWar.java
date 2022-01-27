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

package org.eclipse.jetty.quickstart;

import java.util.Locale;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreconfigureQuickStartWar
{
    private static final Logger LOG = LoggerFactory.getLogger(PreconfigureQuickStartWar.class);
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

        WebAppContext webapp = new WebAppContext();
        webapp.addConfiguration(new QuickStartConfiguration(),
                                new EnvConfiguration(),
                                new PlusConfiguration(),
                                new AnnotationConfiguration());
        webapp.setAttribute(QuickStartConfiguration.MODE, QuickStartConfiguration.Mode.GENERATE);
        webapp.setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, "");
        webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
                ".*/jetty-jakarta-servlet-api-[^/]*\\.jar$|.*/jakarta.servlet.jsp.jstl-.*\\.jar$|.*/.*taglibs-standard-.*\\.jar$");
        if (xml != null)
        {
            if (xml.isDirectory() || !xml.toString().toLowerCase(Locale.ENGLISH).endsWith(".xml"))
                error("Bad context.xml: " + xml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(xml);
            xmlConfiguration.configure(webapp);
        }
        webapp.setResourceBase(dir.getFile().getAbsolutePath());
        server.setHandler(webapp);
        try
        {
            server.setDryRun(true);
            server.start();
        }
        catch (Exception e)
        {
            throw e;
        }
        finally
        {
            if (!server.isStopped())
                server.stop();
        }
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
