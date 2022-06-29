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

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.LoggerFactory;

/**
 * The webapps directory scanning provider.
 * <p>
 * This provider scans one or more directories (typically "webapps") for contexts to
 * deploy, which may be:<ul>
 * <li>A standard WAR file (must end in ".war")</li>
 * <li>A directory containing an expanded WAR file</li>
 * <li>A directory containing static content</li>
 * <li>An XML descriptor in {@link XmlConfiguration} format that configures a {@link ContextHandler} instance</li>
 * </ul>
 * <p>
 * To avoid double deployments and allow flexibility of the content of the scanned directories, the provider
 * implements some heuristics to ignore some files found in the scans: <ul>
 * <li>Hidden files (starting with ".") are ignored</li>
 * <li>Directories with names ending in ".d" are ignored</li>
 * <li>Property files with names ending in ".properties" are not deployed.</li>
 * <li>If a directory and a WAR file exist ( eg foo/ and foo.war) then the directory is assumed to be
 * the unpacked WAR and only the WAR is deployed (which may reused the unpacked directory)</li>
 * <li>If a directory and a matching XML file exist ( eg foo/ and foo.xml) then the directory is assumed to be
 * an unpacked WAR and only the XML is deployed (which may used the directory in it's configuration)</li>
 * <li>If a WAR file and a matching XML exist (eg foo.war and foo.xml) then the WAR is assumed to
 * be configured by the XML and only the XML is deployed.
 * </ul>
 * <p>
 * Only {@link App}s discovered that report {@link App#getEnvironmentName()} matching this providers
 * {@link #getEnvironmentName()} will be deployed.
 * </p>
 * <p>For XML configured contexts, the ID map will contain a reference to the {@link Server} instance called "Server" and
 * properties for the webapp file as "jetty.webapp" and directory as "jetty.webapps".  The properties will be initialized
 * with:<ul>
 *     <li>The properties set on the application via {@link App#getProperties()}; otherwise:</li>
 *     <li>The properties set on this provider via {@link #getProperties()}</li>
 * </ul>
 */
@ManagedObject("Provider for start-up deployement of webapps based on presence in directory")
public class ContextProvider extends ScanningAppProvider
{
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ContextProvider.class);

    private final Map<String, String> _properties = new HashMap<>();

    public class Filter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            if (dir == null || !dir.canRead())
                return false;

            String lowerName = name.toLowerCase(Locale.ENGLISH);

            try (Resource resource = Resource.newResource(new File(dir, name)))
            {
                if (getMonitoredResources().stream().anyMatch(resource::isSame))
                    return false;

                // ignore hidden files
                if (lowerName.startsWith("."))
                    return false;

                // ignore property files
                if (lowerName.endsWith(".properties"))
                    return false;

                // Ignore some directories
                if (resource.isDirectory())
                {
                    // is it a nominated config directory
                    if (lowerName.endsWith(".d"))
                        return false;

                    // is it an unpacked directory for an existing war file?
                    if (exists(name + ".war") || exists(name + ".WAR"))
                        return false;

                    // is it a directory for an existing xml file?
                    if (exists(name + ".xml") || exists(name + ".XML"))
                        return false;

                    //is it a sccs dir?
                    return !"cvs".equals(lowerName) && !"cvsroot".equals(lowerName); // OK to deploy it then
                }
            }

            // else is it a war file
            if (lowerName.endsWith(".war"))
                return true;

            // else is it a context XML file
            return lowerName.endsWith(".xml");
        }
    }

    public ContextProvider()
    {
        super();
        setFilenameFilter(new Filter());
        setScanInterval(0);
    }

    public Map<String, String> getProperties()
    {
        return _properties;
    }

    /**
     * Get the extractWars.
     * This is equivalent to the <code>jetty.deploy.extractWars</code> property.
     *
     * @return the extractWars
     */
    @ManagedAttribute("extract war files")
    public boolean isExtractWars()
    {
        return Boolean.parseBoolean(_properties.get("jetty.deploy.extractWars"));
    }

    /**
     * Set the extractWars.
     * This is equivalent to the <code>jetty.deploy.extractWars</code> property.
     *
     * @param extractWars the extractWars to set
     */
    public void setExtractWars(boolean extractWars)
    {
        _properties.put("jetty.deploy.extractWars", Boolean.toString(extractWars));
    }

    /**
     * Get the parentLoaderPriority.
     * This is equivalent to the <code>jetty.deploy.parentLoaderPriority</code> property.
     *
     * @return the parentLoaderPriority
     */
    @ManagedAttribute("parent classloader has priority")
    public boolean isParentLoaderPriority()
    {
        return Boolean.parseBoolean(_properties.get("jetty.deploy.parentLoaderPriority"));
    }

    /**
     * Set the parentLoaderPriority.
     * This is equivalent to the <code>jetty.deploy.parentLoaderPriority</code> property.
     *
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _properties.put("jetty.deploy.parentLoaderPriority", Boolean.toString(parentLoaderPriority));
    }

    /**
     * Get the defaultsDescriptor.
     * This is equivalent to the <code>jetty.deploy.defaultsDescriptor</code> property.
     *
     * @return the defaultsDescriptor
     */
    @ManagedAttribute("default descriptor for webapps")
    public String getDefaultsDescriptor()
    {
        return _properties.get("jetty.deploy.defaultsDescriptor");
    }

    /**
     * Set the defaultsDescriptor.
     * This is equivalent to the <code>jetty.deploy.defaultsDescriptor</code> property.
     *
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _properties.put("jetty.deploy.defaultsDescriptor", defaultsDescriptor);
    }

    /**
     * This is equivalent to the <code>jetty.deploy.configurationClasses</code> property.
     * @param configurations The configuration class names as a comma separated list
     */
    public void setConfigurationClasses(String configurations)
    {
        setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
    }

    /**
     * This is equivalent to the <code>jetty.deploy.configurationClasses</code> property.
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _properties.put("jetty.deploy.configurationClasses", (configurations == null)
            ? null
            : String.join(",", configurations));
    }

    /**
     *
     * This is equivalent to the <code>jetty.deploy.configurationClasses</code> property.
     * @return The configuration class names.
     */
    @ManagedAttribute("configuration classes for webapps to be processed through")
    public String[] getConfigurationClasses()
    {
        String cc = _properties.get("jetty.deploy.configurationClasses");
        return cc == null ? new String[0] : cc.split(",");
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to the <code>jetty.deploy.tempDir</code> property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(String directory)
    {
        _properties.put("jetty.deploy.tempDir", directory);
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to the <code>jetty.deploy.tempDir</code> property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(File directory)
    {
        _properties.put("jetty.deploy.tempDir", directory.getAbsolutePath());
    }

    /**
     * Get the temporary directory for deployment.
     * <p>
     * This is equivalent to the <code>jetty.deploy.tempDir</code> property.
     *
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    @ManagedAttribute("temp directory for use, null if no user set temp directory")
    public File getTempDir()
    {
        String tmpDir = _properties.get("jetty.deploy.tempDir");
        return tmpDir == null ? null : new File(tmpDir);
    }

    protected ContextHandler initializeContextHandler(Object context, File file, Map<String, String> properties)
    {
        // find the ContextHandler
        ContextHandler contextHandler;
        if (context instanceof ContextHandler handler)
            contextHandler = handler;
        else if (Supplier.class.isAssignableFrom(context.getClass()))
        {
            @SuppressWarnings("unchecked")
            Supplier<ContextHandler> provider = (Supplier<ContextHandler>)context;
            contextHandler = provider.get();
        }
        else
        {
            throw new IllegalStateException("No ContextHandler for " + context);
        }

        assert contextHandler != null;

        initializeContextPath(contextHandler, file.getName());

        if (file.isDirectory())
            contextHandler.setBaseResource(Resource.newResource(file));

        if (context instanceof Deployable deployable)
            deployable.initializeDefaults(properties);

        return contextHandler;
    }

    @Override
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        Environment environment = Environment.get(app.getEnvironmentName());

        if (LOG.isDebugEnabled())
            LOG.debug("createContextHandler {} in {}", app, environment);

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(environment.getClassLoader());

            // Create de-aliased file
            File file = new File(app.getFilename()).toPath().toRealPath().toFile().getCanonicalFile().getAbsoluteFile();
            if (!file.exists())
                throw new IllegalStateException("App resource does not exist " + file);

            // prepare properties
            Map<String, String> properties = new HashMap<>();
            properties.putAll(getProperties());
            properties.putAll(app.getProperties());

            // Handle a context XML file
            if (FileID.isXmlFile(file))
            {
                XmlConfiguration xmlc = new XmlConfiguration(file, null, properties)
                {
                    @Override
                    public void initializeDefaults(Object context)
                    {
                        super.initializeDefaults(context);
                        ContextProvider.this.initializeContextHandler(context, file, properties);
                    }
                };

                xmlc.getIdMap().put("Environment", environment);
                xmlc.setJettyStandardIdsAndProperties(getDeploymentManager().getServer(), file);

                Object context = xmlc.configure();
                if (context instanceof ContextHandler contextHandler)
                    return contextHandler;
                if (context instanceof Supplier<?> supplier)
                {
                    Object nestedContext = supplier.get();
                    if (nestedContext instanceof ContextHandler contextHandler)
                        return contextHandler;
                }
                throw new IllegalStateException("Unknown context type of " + context);
            }
            // Otherwise it must be a directory or an archive
            else if (!file.isDirectory() && !FileID.isWebArchiveFile(file))
            {
                throw new IllegalStateException("unable to create ContextHandler for " + app);
            }

            // Build the web application
            @SuppressWarnings("unchecked")
            String contextHandlerClassName = (String)environment.getAttribute("contextHandlerClass");
            if (StringUtil.isBlank(contextHandlerClassName))
                throw new IllegalStateException("No ContextHandler classname for " + app);
            Class<?> contextHandlerClass = Loader.loadClass(contextHandlerClassName);
            if (contextHandlerClass == null)
                throw new IllegalStateException("Unknown ContextHandler class " + contextHandlerClassName + " for " + app);

            Object context = contextHandlerClass.getDeclaredConstructor().newInstance();
            properties.put(Deployable.WAR, file.getCanonicalPath());
            return initializeContextHandler(context, file, properties);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    protected void initializeContextPath(ContextHandler context, String contextName)
    {
        // Strip any 3 char extension from non directories
        String contextPath = FileID.getDot3Basename(contextName);

        // Ensure "/" is Not Trailing in context paths.
        if (contextPath.endsWith("/") && contextPath.length() > 1)
            contextPath = contextPath.substring(0, contextPath.length() - 1);

        // special case of archive (or dir) named "root" is / context
        if (contextPath.equalsIgnoreCase("root"))
        {
            contextPath = URIUtil.SLASH;
        }
        // handle root with virtual host form
        else if (StringUtil.startsWithIgnoreCase(contextPath, "root-"))
        {
            int dash = contextPath.indexOf('-');
            String virtual = contextPath.substring(dash + 1);
            context.setVirtualHosts(Arrays.asList(virtual.split(",")));
            contextPath = URIUtil.SLASH;
        }

        // Ensure "/" is Prepended to all context paths.
        if (contextPath.charAt(0) != '/')
            contextPath = "/" + contextPath;

        // Set the display name and context Path
        context.setDisplayName(contextName);
        context.setContextPath(contextPath);
    }

    @Override
    protected void fileChanged(String filename) throws Exception
    {
        File file = new File(filename);
        if (!file.exists())
            return;

        File parent = file.getParentFile();

        //is the file that changed a directory? 
        if (file.isDirectory())
        {
            //is there a .xml file of the same name?
            if (exists(file.getName() + ".xml") || exists(file.getName() + ".XML"))
                return; //ignore it

            //is there .war file of the same name?
            if (exists(file.getName() + ".war") || exists(file.getName() + ".WAR"))
                return; //ignore it

            super.fileChanged(filename);
            return;
        }

        String lowname = file.getName().toLowerCase(Locale.ENGLISH);
        //is the file that changed a .war file?
        if (lowname.endsWith(".war"))
        {
            String name = file.getName();
            String base = name.substring(0, name.length() - 4);
            String xmlname = base + ".xml";
            if (exists(xmlname))
            {
                //if a .xml file exists for it, then redeploy that instead
                File xml = new File(parent, xmlname);
                super.fileChanged(xml.getPath());
                return;
            }

            xmlname = base + ".XML";
            if (exists(xmlname))
            {
                //if a .XML file exists for it, then redeploy that instead
                File xml = new File(parent, xmlname);
                super.fileChanged(xml.getPath());
                return;
            }

            //redeploy the changed war
            super.fileChanged(filename);
            return;
        }

        //is the file that changed a .xml file?
        if (lowname.endsWith(".xml"))
            super.fileChanged(filename);
    }

    @Override
    protected void fileAdded(String filename) throws Exception
    {
        File file = new File(filename);
        if (!file.exists())
            return;

        //is the file that was added a directory? 
        if (file.isDirectory())
        {
            //is there a .xml file of the same name?
            if (exists(file.getName() + ".xml") || exists(file.getName() + ".XML"))
                return; //assume we will get added events for the xml file

            //is there .war file of the same name?
            if (exists(file.getName() + ".war") || exists(file.getName() + ".WAR"))
                return; //assume we will get added events for the war file

            //is there .jar file of the same name?
            if (exists(file.getName() + ".jar") || exists(file.getName() + ".JAR"))
                return; //assume we will get added events for the jar file

            super.fileAdded(filename);
            return;
        }

        //is the file that was added a .war file?
        String lowname = file.getName().toLowerCase(Locale.ENGLISH);
        if (lowname.endsWith(".war"))
        {
            String name = file.getName();
            String base = name.substring(0, name.length() - 4);
            //is there a .xml file of the same name?
            if (exists(base + ".xml") || exists(base + ".XML"))
                return; //ignore it as we should get addition of the xml file

            super.fileAdded(filename);
            return;
        }

        //is the file that was added an .xml file?
        if (lowname.endsWith(".xml"))
            super.fileAdded(filename);
    }

    @Override
    protected void fileRemoved(String filename) throws Exception
    {
        File file = new File(filename);

        //is the file that was removed a .war file?
        String lowname = file.getName().toLowerCase(Locale.ENGLISH);
        if (lowname.endsWith(".war"))
        {
            //is there a .xml file of the same name?
            String name = file.getName();
            String base = name.substring(0, name.length() - 4);
            if (exists(base + ".xml") || exists(base + ".XML"))
                return; //ignore it as we should get removal of the xml file

            super.fileRemoved(filename);
            return;
        }

        //is the file that was removed an .xml file?
        if (lowname.endsWith(".xml"))
        {
            super.fileRemoved(filename);
            return;
        }

        //is there a .xml file of the same name?
        if (exists(file.getName() + ".xml") || exists(file.getName() + ".XML"))
            return; //assume we will get removed events for the xml file

        //is there .war file of the same name?
        if (exists(file.getName() + ".war") || exists(file.getName() + ".WAR"))
            return; //assume we will get removed events for the war file

        super.fileRemoved(filename);
    }
}
