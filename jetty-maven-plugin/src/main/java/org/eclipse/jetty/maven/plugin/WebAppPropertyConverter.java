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

package org.eclipse.jetty.maven.plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * WebAppPropertyConverter
 *
 * Converts a webapp's configuration to a properties file, and
 * vice versa.
 */
public class WebAppPropertyConverter
{

    /**
     * Convert a webapp to properties stored in a file.
     *
     * @param webApp the webapp to convert
     * @param propsFile the file to put the properties into
     * @param contextXml the optional context xml file related to the webApp
     * @throws Exception if any I/O exception occurs
     */
    public static void toProperties(JettyWebAppContext webApp, File propsFile, String contextXml)
        throws Exception
    {
        if (webApp == null)
            throw new IllegalArgumentException("No webapp");
        if (propsFile == null)
            throw new IllegalArgumentException("No properties file");

        //work out the configuration based on what is configured in the pom
        if (propsFile.exists())
            propsFile.delete();

        propsFile.createNewFile();

        Properties props = new Properties();
        //web.xml
        if (webApp.getDescriptor() != null)
        {
            props.put("web.xml", webApp.getDescriptor());
        }

        if (webApp.getQuickStartWebDescriptor() != null)
        {
            props.put("quickstart.web.xml", webApp.getQuickStartWebDescriptor().getFile().getAbsolutePath());
        }

        //sort out the context path
        if (webApp.getContextPath() != null)
        {
            props.put("context.path", webApp.getContextPath());
        }

        //tmp dir
        props.put("tmp.dir", webApp.getTempDirectory().getAbsolutePath());
        //props.put("tmp.dir.persist", Boolean.toString(originalPersistTemp));
        props.put("tmp.dir.persist", Boolean.toString(webApp.isPersistTempDirectory()));

        //send over the calculated resource bases that includes unpacked overlays
        Resource baseResource = webApp.getBaseResource();
        if (baseResource instanceof ResourceCollection)
            props.put("base.dirs", toCSV(((ResourceCollection)webApp.getBaseResource()).getResources()));
        else
            props.put("base.dirs", webApp.getBaseResource().toString());

        //web-inf classes
        if (webApp.getClasses() != null)
        {
            props.put("classes.dir", webApp.getClasses().getAbsolutePath());
        }

        if (webApp.getTestClasses() != null)
        {
            props.put("testClasses.dir", webApp.getTestClasses().getAbsolutePath());
        }

        //web-inf lib
        List<File> deps = webApp.getWebInfLib();
        StringBuilder strbuff = new StringBuilder();
        if (deps != null)
        {
            for (int i = 0; i < deps.size(); i++)
            {
                File d = deps.get(i);
                strbuff.append(d.getAbsolutePath());
                if (i < deps.size() - 1)
                    strbuff.append(",");
            }
        }
        props.put("lib.jars", strbuff.toString());

        //context xml to apply
        if (contextXml != null)
            props.put("context.xml", contextXml);

        try (BufferedWriter out = Files.newBufferedWriter(propsFile.toPath()))
        {
            props.store(out, "properties for forked webapp");
        }
    }

    /**
     * Configure a webapp from a properties file.
     *
     * @param webApp the webapp to configure
     * @param resource the properties file to apply
     * @param server the Server instance to use
     * @param jettyProperties jetty properties to use if there is a context xml file to apply
     * @throws Exception if any I/O exception occurs
     */
    public static void fromProperties(JettyWebAppContext webApp, String resource, Server server, Map<String, String> jettyProperties)
        throws Exception
    {
        if (resource == null)
            throw new IllegalStateException("No resource");

        fromProperties(webApp, Resource.newResource(resource).getFile(), server, jettyProperties);
    }

    /**
     * Configure a webapp from a properties file
     *
     * @param webApp the webapp to configure
     * @param propsFile the properties to apply
     * @param server the Server instance to use if there is a context xml file to apply
     * @param jettyProperties jetty properties to use if there is a context xml file to apply
     * @throws Exception if any I/O exception occurs
     */
    public static void fromProperties(JettyWebAppContext webApp, File propsFile, Server server, Map<String, String> jettyProperties)
        throws Exception
    {
        if (webApp == null)
            throw new IllegalArgumentException("No webapp");
        if (propsFile == null)
            throw new IllegalArgumentException("No properties file");

        if (!propsFile.exists())
            throw new IllegalArgumentException(propsFile.getCanonicalPath() + " does not exist");

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsFile.toPath()))
        {
            props.load(in);
        }

        String str = props.getProperty("context.path");
        if (!StringUtil.isBlank(str))
            webApp.setContextPath(str);

        // - web.xml
        str = props.getProperty("web.xml");
        if (!StringUtil.isBlank(str))
            webApp.setDescriptor(str);

        //TODO the WebAppStarter class doesn't set up the QUICKSTART_CONFIGURATION_CLASSES, but the Starter class does!!!
        str = props.getProperty("quickstart.web.xml");
        if (!StringUtil.isBlank(str))
        {
            webApp.setQuickStartWebDescriptor(Resource.newResource(new File(str)));
            webApp.setConfigurationClasses(JettyWebAppContext.QUICKSTART_CONFIGURATION_CLASSES);
        }

        // - the tmp directory
        str = props.getProperty("tmp.dir");
        if (!StringUtil.isBlank(str))
            webApp.setTempDirectory(new File(str.trim()));

        str = props.getProperty("tmp.dir.persist");
        if (!StringUtil.isBlank(str))
            webApp.setPersistTempDirectory(Boolean.valueOf(str));

        //Get the calculated base dirs which includes the overlays
        str = props.getProperty("base.dirs");
        if (!StringUtil.isBlank(str))
        {
            ResourceCollection bases = new ResourceCollection(StringUtil.csvSplit(str));
            webApp.setWar(null);
            webApp.setBaseResource(bases);
        }

        // - the equivalent of web-inf classes
        str = props.getProperty("classes.dir");
        if (!StringUtil.isBlank(str))
        {
            webApp.setClasses(new File(str));
        }

        str = props.getProperty("testClasses.dir");
        if (!StringUtil.isBlank(str))
        {
            webApp.setTestClasses(new File(str));
        }

        // - the equivalent of web-inf lib
        str = props.getProperty("lib.jars");
        if (!StringUtil.isBlank(str))
        {
            List<File> jars = new ArrayList<File>();
            String[] names = StringUtil.csvSplit(str);
            for (int j = 0; names != null && j < names.length; j++)
            {
                jars.add(new File(names[j].trim()));
            }
            webApp.setWebInfLib(jars);
        }

        //set up the webapp from the context xml file provided
        //NOTE: just like jetty:run mojo this means that the context file can
        //potentially override settings made in the pom. Ideally, we'd like
        //the pom to override the context xml file, but as the other mojos all
        //configure a WebAppContext in the pom (the <webApp> element), it is 
        //already configured by the time the context xml file is applied.
        str = props.getProperty("context.xml");
        if (!StringUtil.isBlank(str))
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(str).getURI().toURL());
            xmlConfiguration.getIdMap().put("Server", server);
            //add in any properties
            if (jettyProperties != null)
            {
                for (Map.Entry<String, String> prop : jettyProperties.entrySet())
                {
                    xmlConfiguration.getProperties().put(prop.getKey(), prop.getValue());
                }
            }
            xmlConfiguration.configure(webApp);
        }
    }

    /**
     * Convert an array of Resources to csv file names
     *
     * @param resources the resources to convert
     * @return csv string of resource filenames
     */
    private static String toCSV(Resource[] resources)
    {
        StringBuilder rb = new StringBuilder();

        for (Resource r : resources)
        {
            if (rb.length() > 0)
                rb.append(",");
            rb.append(r.toString());
        }

        return rb.toString();
    }
}
