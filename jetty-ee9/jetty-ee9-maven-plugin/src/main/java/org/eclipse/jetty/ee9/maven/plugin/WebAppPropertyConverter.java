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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.jetty.ee9.quickstart.QuickStartConfiguration;
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
    public static String WEB_XML = "web.xml";
    public static String QUICKSTART_WEB_XML = "quickstart.web.xml";
    public static String CONTEXT_XML = "context.xml";
    public static String CONTEXT_PATH = "context.path";
    public static String TMP_DIR = "tmp.dir";
    public static String TMP_DIR_PERSIST = "tmp.dir.persist";
    public static String BASE_DIRS = "base.dirs";
    public static String WAR_FILE = "war.file";
    public static String CLASSES_DIR = "classes.dir";
    public static String TEST_CLASSES_DIR = "testClasses.dir";
    public static String LIB_JARS = "lib.jars";
    public static String DEFAULTS_DESCRIPTOR = "web.default.xml";
    public static String OVERRIDE_DESCRIPTORS = "web.overrides.xml";
    
    //TODO :Support defaults descriptor!
    
    /**
     * Convert a webapp to properties stored in a file.
     *
     * @param webApp the webapp to convert
     * @param propsFile the file to put the properties into
     * @param contextXml the optional context xml file related to the webApp
     * @throws Exception if any I/O exception occurs
     */
    public static void toProperties(MavenWebAppContext webApp, File propsFile, String contextXml)
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
            props.put(WEB_XML, webApp.getDescriptor());
        }

        Object tmp = webApp.getAttribute(QuickStartConfiguration.QUICKSTART_WEB_XML);
        if (tmp != null)
        {
            props.put(QUICKSTART_WEB_XML, tmp.toString());
        }

        //sort out the context path
        if (webApp.getContextPath() != null)
        {
            props.put(CONTEXT_PATH, webApp.getContextPath());
        }

        //tmp dir
        props.put(TMP_DIR, webApp.getTempDirectory().getAbsolutePath());
        //props.put("tmp.dir.persist", Boolean.toString(originalPersistTemp));
        props.put(TMP_DIR_PERSIST, Boolean.toString(webApp.isPersistTempDirectory()));

        //send over the calculated resource bases that includes unpacked overlays
        Resource baseResource = webApp.getBaseResource();
        if (baseResource instanceof ResourceCollection)
            props.put(BASE_DIRS, toCSV(((ResourceCollection)webApp.getBaseResource()).getResources()));
        else if (baseResource instanceof Resource)
            props.put(BASE_DIRS, webApp.getBaseResource().toString());
        
        //if there is a war file, use that
        if (webApp.getWar() != null)
            props.put(WAR_FILE, webApp.getWar());

        //web-inf classes
        if (webApp.getClasses() != null)
        {
            props.put(CLASSES_DIR, webApp.getClasses().getAbsolutePath());
        }

        if (webApp.getTestClasses() != null)
        {
            props.put(TEST_CLASSES_DIR, webApp.getTestClasses().getAbsolutePath());
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
        props.put(LIB_JARS, strbuff.toString());

        //context xml to apply
        if (contextXml != null)
            props.put(CONTEXT_XML, contextXml);
        
        if (webApp.getDefaultsDescriptor() != null)
            props.put(DEFAULTS_DESCRIPTOR, webApp.getDefaultsDescriptor());
        
        if (webApp.getOverrideDescriptors() != null)
        {
            props.put(OVERRIDE_DESCRIPTORS, String.join(",", webApp.getOverrideDescriptors()));
        }

        try (BufferedWriter out = Files.newBufferedWriter(propsFile.toPath()))
        {
            props.store(out, "properties for webapp");
        }
    }

    /**
     * Configure a webapp from a properties file.
     *
     * @param webApp the webapp to configure
     * @param resource the properties file to apply
     * @param server the Server instance to use
     * @param jettyProperties jetty properties to use if there is a context xml file to apply
     * @throws Exception
     */
    public static void fromProperties(MavenWebAppContext webApp, String resource, Server server, Map<String, String> jettyProperties)
        throws Exception
    {
        if (resource == null)
            throw new IllegalStateException("No resource");

        fromProperties(webApp, Resource.newResource(resource).getPath(), server, jettyProperties);
    }

    /**
     * Configure a webapp from properties.
     * 
     * @param webApp the webapp to configure
     * @param webAppProperties properties that describe the configuration of the webapp
     * @param server the jetty Server instance
     * @param jettyProperties jetty properties
     * 
     * @throws Exception
     */
    public static void fromProperties(MavenWebAppContext webApp, Properties webAppProperties, Server server, Map<String, String> jettyProperties)
        throws Exception
    {
        if (webApp == null)
            throw new IllegalArgumentException("No webapp");
        
        if (webAppProperties == null)
            return;

        String str = webAppProperties.getProperty(CONTEXT_PATH);
        if (!StringUtil.isBlank(str))
            webApp.setContextPath(str);

        // - web.xml
        str = webAppProperties.getProperty(WEB_XML);
        if (!StringUtil.isBlank(str))
            webApp.setDescriptor(str);

        //if there is a pregenerated quickstart file
        str = webAppProperties.getProperty(QUICKSTART_WEB_XML);
        if (!StringUtil.isBlank(str))
        {
            webApp.setAttribute(QuickStartConfiguration.QUICKSTART_WEB_XML, Resource.newResource(str));
        }

        // - the tmp directory
        str = webAppProperties.getProperty(TMP_DIR);
        if (!StringUtil.isBlank(str))
            webApp.setTempDirectory(new File(str.trim()));

        str = webAppProperties.getProperty(TMP_DIR_PERSIST);
        if (!StringUtil.isBlank(str))
            webApp.setPersistTempDirectory(Boolean.valueOf(str));

        //Get the calculated base dirs which includes the overlays
        str = webAppProperties.getProperty(BASE_DIRS);
        if (!StringUtil.isBlank(str))
        {
            webApp.setWar(null);
            // This is a use provided list of overlays, which could have mountable entries.
            List<URI> uris = Resource.split(str);
            webApp.setBaseResource(Resource.newResource(uris, webApp));
        }

        str = webAppProperties.getProperty(WAR_FILE);
        if (!StringUtil.isBlank(str))
        {
            webApp.setWar(str);
        }
        
        // - the equivalent of web-inf classes
        str = webAppProperties.getProperty(CLASSES_DIR);
        if (!StringUtil.isBlank(str))
        {
            webApp.setClasses(new File(str));
        }

        str = webAppProperties.getProperty(TEST_CLASSES_DIR);
        if (!StringUtil.isBlank(str))
        {
            webApp.setTestClasses(new File(str));
        }

        // - the equivalent of web-inf lib
        str = webAppProperties.getProperty(LIB_JARS);
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

        //any defaults descriptor
        str = (String)webAppProperties.getProperty(DEFAULTS_DESCRIPTOR);
        if (!StringUtil.isBlank(str))
        {
            webApp.setDefaultsDescriptor(str);
        }
        
        //any override descriptors
        str = (String)webAppProperties.getProperty(OVERRIDE_DESCRIPTORS);
        if (!StringUtil.isBlank(str))
        {
            String[] names = StringUtil.csvSplit(str);
            for (int j = 0; names != null && j < names.length; j++)
            {
                webApp.addOverrideDescriptor(names[j]);
            }
        }
        
        //set up the webapp from the context xml file provided
        //NOTE: just like jetty:run mojo this means that the context file can
        //potentially override settings made in the pom. Ideally, we'd like
        //the pom to override the context xml file, but as the other mojos all
        //configure a WebAppContext in the pom (the <webApp> element), it is 
        //already configured by the time the context xml file is applied.
        str = (String)webAppProperties.getProperty(CONTEXT_XML);
        if (!StringUtil.isBlank(str))
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(Resource.newResource(str));
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
     * Configure a webapp from a properties file
     * @param webApp the webapp to configure
     * @param propsFile the properties to apply
     * @param server the Server instance to use if there is a context xml file to apply
     * @param jettyProperties jetty properties to use if there is a context xml file to apply
     * @throws Exception
     */
    public static void fromProperties(MavenWebAppContext webApp, Path propsFile, Server server, Map<String, String> jettyProperties)
        throws Exception
    {

        if (propsFile == null)
            throw new IllegalArgumentException("No properties file");
        
        if (!Files.exists(propsFile))
            throw new IllegalArgumentException(propsFile + " does not exist");
        
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsFile))
        {
            props.load(in);
        }
       
        fromProperties(webApp, props, server, jettyProperties);
    }

    /**
     * Convert an array of Resources to csv file names
     *
     * @param resources the resources to convert
     * @return csv string of resource filenames
     */
    private static String toCSV(List<Resource> resources)
    {
        return resources.stream().map(Object::toString).collect(Collectors.joining(","));
    }
}
