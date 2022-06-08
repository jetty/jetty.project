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
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.ConfigurationManager;
import org.eclipse.jetty.deploy.util.FileID;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IO;
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
 * <li>If a directory and a WAR file exist ( eg foo/ and foo.war) then the directory is assumed to be
 * the unpacked WAR and only the WAR is deployed (which may reused the unpacked directory)</li>
 * <li>If a directory and a matching XML file exist ( eg foo/ and foo.xml) then the directory is assumed to be
 * an unpacked WAR and only the XML is deployed (which may used the directory in it's configuration)</li>
 * <li>If a WAR file and a matching XML exist (eg foo.war and foo.xml) then the WAR is assumed to
 * be configured by the XML and only the XML is deployed.
 * </ul>
 * <p>For XML configured contexts, the ID map will contain a reference to the {@link Server} instance called "Server" and
 * properties for the webapp file as "jetty.webapp" and directory as "jetty.webapps".
 */
@ManagedObject("Provider for start-up deployement of webapps based on presence in directory")
public class ContextProvider extends ScanningAppProvider
{
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ContextProvider.class);

    private boolean _extract = false;
    private boolean _parentLoaderPriority = false;
    private ConfigurationManager _configurationManager;
    private String _defaultsDescriptor;
    private File _tempDirectory;
    private String[] _configurationClasses;

    public class Filter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            if (dir == null || !dir.canRead())
                return false;

            String lowerName = name.toLowerCase(Locale.ENGLISH);

            Resource resource = Resource.newResource(new File(dir, name).toPath()); // TODO use paths
            if (getMonitoredResources().stream().anyMatch(resource::isSame))
                return false;

            // ignore hidden files
            if (lowerName.startsWith("."))
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

    /**
     * Get the extractWars.
     *
     * @return the extractWars
     * @deprecated use {@link #isExtract()}
     */
    @Deprecated
    public boolean isExtractWars()
    {
        return isExtract();
    }

    /**
     * @return True if WAR and JAR are extraced on deploy.
     */
    @ManagedAttribute("extract WAR and JAR files")
    public boolean isExtract()
    {
        return _extract;
    }

    /**
     * Set the extractWars.
     *
     * @param extract the extractWars to set
     * @deprecated use {@link #setExtract(boolean)}
     */
    @Deprecated
    public void setExtractWars(boolean extract)
    {
        setExtract(extract);
    }

    /**
     * Set to extract WAR and JAR files.
     *
     * @param extract the extractWars to set
     */
    public void setExtract(boolean extract)
    {
        _extract = extract;
    }

    /**
     * Get the parentLoaderPriority.
     *
     * @return the parentLoaderPriority
     */
    @ManagedAttribute("parent classloader has priority")
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /**
     * Set the parentLoaderPriority.
     *
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _parentLoaderPriority = parentLoaderPriority;
    }

    /**
     * Get the defaultsDescriptor.
     *
     * @return the defaultsDescriptor
     */
    @ManagedAttribute("default descriptor for webapps")
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /**
     * Set the defaultsDescriptor.
     *
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    public ConfigurationManager getConfigurationManager()
    {
        return _configurationManager;
    }

    /**
     * Set the configurationManager.
     *
     * @param configurationManager the configurationManager to set
     */
    public void setConfigurationManager(ConfigurationManager configurationManager)
    {
        updateBean(_configurationManager, configurationManager);
        _configurationManager = configurationManager;
    }

    /**
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _configurationClasses = configurations == null ? null : configurations.clone();
    }

    @ManagedAttribute("configuration classes for webapps to be processed through")
    public String[] getConfigurationClasses()
    {
        return _configurationClasses;
    }

    /**
     * Set the Work directory where unpacked WAR files are managed from.
     * <p>
     * Default is the same as the <code>java.io.tmpdir</code> System Property.
     *
     * @param directory the new work directory
     */
    public void setTempDir(File directory)
    {
        _tempDirectory = directory;
    }

    /**
     * Get the user supplied Work Directory.
     *
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    @ManagedAttribute("temp directory for use, null if no user set temp directory")
    public File getTempDir()
    {
        return _tempDirectory;
    }

    protected void initializeWebAppContextDefaults(ContextHandler webapp)
    {
        if (_defaultsDescriptor != null)
            webapp.setAttribute("defaultsDescriptor", _defaultsDescriptor);
        /*
        webapp.setExtractWAR(_extractWars);
        webapp.setParentLoaderPriority(_parentLoaderPriority);
        if (_configurationClasses != null)
            webapp.setConfigurationClasses(_configurationClasses);

        */

        if (_tempDirectory != null)
        {
            // Since the Temp Dir is really a context base temp directory,
            // Lets set the Temp Directory in a way similar to how WebInfConfiguration does it,
            // instead of setting the WebAppContext.setTempDirectory(File).
            // If we used .setTempDirectory(File) all webapps will wind up in the
            // same temp / work directory, overwriting each others work.
            webapp.setAttribute(Server.BASE_TEMP_DIR_ATTR, _tempDirectory);
        }
    }

    @Override
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        Environment environment = Environment.get(app.getEnvironment());

        if (LOG.isDebugEnabled())
            LOG.debug("createContextHandler {} in {}", app, environment);

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(environment.getClassLoader());

            Resource resource = Resource.newResource(app.getFilename());
            if (!resource.exists())
                throw new IllegalStateException("App resource does not exist " + resource);
            resource = unpack(resource);

            Path path = resource.getPath();


            final String contextName = path.getFileName().toString();

            // Resource aliases (after getting name) to ensure baseResource is not an alias
            if (resource.isAlias())
            {
                path = Paths.get(resource.getAlias()).toRealPath();
                resource = Resource.newResource(path);
                if (!resource.exists())
                    throw new IllegalStateException("App resource does not exist " + resource);
            }

            // Handle a context XML file
            if (resource.exists() && FileID.isXmlFile(path))
            {
                XmlConfiguration xmlc = new XmlConfiguration(resource)
                {
                    @Override
                    public void initializeDefaults(Object context)
                    {
                        super.initializeDefaults(context);

                        // If the XML created object is a ContextHandler
                        if (context instanceof ContextHandler)
                            // Initialize the context path prior to running context XML
                            initializeContextPath((ContextHandler)context, contextName, true);

                        if (context instanceof ContextHandler contextHandler)
                            initializeWebAppContextDefaults(contextHandler);
                    }
                };

                xmlc.getIdMap().put("Environment", environment);
                getDeploymentManager().scope(xmlc, resource);
                if (getConfigurationManager() != null)
                    xmlc.getProperties().putAll(getConfigurationManager().getProperties());
                return (ContextHandler)xmlc.configure();
            }
            // Otherwise it must be a directory or an archive
            else if (!Files.isDirectory(path) && !FileID.isWebArchiveFile(path))
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
            ContextHandler contextHandler;
            if (Supplier.class.isAssignableFrom(contextHandlerClass))
            {
                @SuppressWarnings("unchecked")
                Supplier<ContextHandler> provider = (Supplier<ContextHandler>)contextHandlerClass.getDeclaredConstructor().newInstance();
                contextHandler = provider.get();
            }
            else
            {
                contextHandler = (ContextHandler)contextHandlerClass.getDeclaredConstructor().newInstance();
            }
            contextHandler.setBaseResource(Resource.newResource(path));
            initializeContextPath(contextHandler, contextName, !Files.isDirectory(path));
            initializeWebAppContextDefaults(contextHandler);

            return contextHandler;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    protected void initializeContextPath(ContextHandler context, String contextName, boolean stripExtension)
    {
        String contextPath = contextName;

        // Strip any 3 char extension from non directories
        if (stripExtension && contextPath.length() > 4 && contextPath.charAt(contextPath.length() - 4) == '.')
            contextPath = contextPath.substring(0, contextPath.length() - 4);

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

    public Resource unpack(Resource resourceBase) throws IOException
    {
        // Accept aliases for WAR files
        if (resourceBase.isAlias())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} anti-aliased to {}", resourceBase, resourceBase.getAlias());
            URI alias = resourceBase.getAlias();
            resourceBase.close();
            resourceBase = Resource.newResource(alias);
        }

        if (!isExtract() || resourceBase.isDirectory() || resourceBase.getPath() == null)
            return resourceBase;

        // is the extension a known extension
        if (!resourceBase.getPath().getFileName().toString().toLowerCase().endsWith(".war") &&
            !resourceBase.getPath().getFileName().toString().toLowerCase().endsWith(".jar"))
            return resourceBase;

        // Track the original web_app Resource, as this could be a PathResource.
        // Later steps force the Resource to be a JarFileResource, which introduces
        // URLConnection caches in such a way that it prevents Hot Redeployment
        // on MS Windows.
        Resource originalResource = resourceBase;

        // Look for unpacked directory
        Path path = resourceBase.getPath();
        String name = path.getName(path.getNameCount() - 1).toString();
        name = name.substring(0, name.length() - 4);
        Path directory = path.getParent(); // TODO support unpacking to temp or work directory
        File unpacked = directory.resolve(name).toFile();
        File extractLock = directory.resolve(".extract_lock").toFile();

        if (!Files.isWritable(directory))
        {
            LOG.warn("!Writable {} -> {}", resourceBase, directory);
            return resourceBase;
        }

        // Does the directory already exist and is newer than the packed file?
        if (unpacked.exists())
        {
            // If it is not a directory, then we can't unpack
            if (!unpacked.isDirectory())
            {
                LOG.warn("Unusable {} -> {}", resourceBase, unpacked);
                return resourceBase;
            }

            // If it is newer than the resource base and there is no partial extraction, then use it.
            if (Files.getLastModifiedTime(directory).toMillis() >= resourceBase.lastModified() && !extractLock.exists())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Reuse {} -> {}", resourceBase, unpacked);
                resourceBase.close();
                return Resource.newResource(unpacked.toPath());
            }

            extractLock.createNewFile();
            IO.delete(unpacked);
        }
        else
        {
            extractLock.createNewFile();
        }

        if (!unpacked.mkdir())
        {
            LOG.warn("Cannot Create {} -> {}", resourceBase, unpacked);
            extractLock.delete();
            return resourceBase;
        }

        LOG.debug("Unpack {} -> {}", resourceBase, unpacked);
        resourceBase.copyTo(unpacked.toPath());

        extractLock.delete();
        resourceBase.close();

        return Resource.newResource(unpacked.toPath());
    }
}
