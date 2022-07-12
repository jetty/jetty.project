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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
 * properties for the webapp file such as "jetty.webapp" and directory as "jetty.webapps".
 * The properties will be initialized with:<ul>
 *     <li>The properties set on the application via {@link App#getProperties()}; otherwise:</li>
 *     <li>The properties set on this provider via {@link #getProperties()}</li>
 * </ul>
 */
@ManagedObject("Provider for start-up deployment of webapps based on presence in directory")
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

            // Accept XML files and WARs
            if (FileID.isXml(name) || FileID.isWebArchive(name))
                return true;

            Path path = dir.toPath().resolve(name);

            // Ignore any other file that are not directories
            if (!Files.isDirectory(path))
                return false;

            // Don't deploy monitored resources
            if (getMonitoredResources().stream().map(Resource::getPath).anyMatch(path::equals))
                return false;

            // Ignore hidden directories
            if (name.startsWith("."))
                return false;

            String lowerName = name.toLowerCase(Locale.ENGLISH);

            // is it a nominated config directory
            if (lowerName.endsWith(".d"))
                return false;

            // ignore source control directories
            if ("cvs".equals(lowerName) || "cvsroot".equals(lowerName))
                return false;

            // ignore directories that have sibling war or XML file
            if (Files.exists(dir.toPath().resolve(name + ".war")) ||
                Files.exists(dir.toPath().resolve(name + ".WAR")) ||
                Files.exists(dir.toPath().resolve(name + ".xml")) ||
                Files.exists(dir.toPath().resolve(name + ".XML")))
                return false;

            return true;
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
     * This is equivalent to getting the {@link Deployable#EXTRACT_WARS} property.
     *
     * @return the extractWars
     */
    @ManagedAttribute("extract war files")
    public boolean isExtractWars()
    {
        return Boolean.parseBoolean(_properties.get(Deployable.EXTRACT_WARS));
    }

    /**
     * Set the extractWars.
     * This is equivalent to setting the {@link Deployable#EXTRACT_WARS} property.
     *
     * @param extractWars the extractWars to set
     */
    public void setExtractWars(boolean extractWars)
    {
        _properties.put(Deployable.EXTRACT_WARS, Boolean.toString(extractWars));
    }

    /**
     * Get the parentLoaderPriority.
     * This is equivalent to getting the {@link Deployable#PARENT_LOADER_PRIORITY} property.
     *
     * @return the parentLoaderPriority
     */
    @ManagedAttribute("parent classloader has priority")
    public boolean isParentLoaderPriority()
    {
        return Boolean.parseBoolean(_properties.get(Deployable.PARENT_LOADER_PRIORITY));
    }

    /**
     * Set the parentLoaderPriority.
     * This is equivalent to setting the {@link Deployable#PARENT_LOADER_PRIORITY} property.
     *
     * @param parentLoaderPriority the parentLoaderPriority to set
     */
    public void setParentLoaderPriority(boolean parentLoaderPriority)
    {
        _properties.put(Deployable.PARENT_LOADER_PRIORITY, Boolean.toString(parentLoaderPriority));
    }

    /**
     * Get the defaultsDescriptor.
     * This is equivalent to getting the {@link Deployable#DEFAULTS_DESCRIPTOR} property.
     *
     * @return the defaultsDescriptor
     */
    @ManagedAttribute("default descriptor for webapps")
    public String getDefaultsDescriptor()
    {
        return _properties.get(Deployable.DEFAULTS_DESCRIPTOR);
    }

    /**
     * Set the defaultsDescriptor.
     * This is equivalent to setting the {@link Deployable#DEFAULTS_DESCRIPTOR} property.
     *
     * @param defaultsDescriptor the defaultsDescriptor to set
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _properties.put(Deployable.DEFAULTS_DESCRIPTOR, defaultsDescriptor);
    }

    /**
     * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @param configurations The configuration class names as a comma separated list
     */
    public void setConfigurationClasses(String configurations)
    {
        setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
    }

    /**
     * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @param configurations The configuration class names.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _properties.put(Deployable.CONFIGURATION_CLASSES, (configurations == null)
            ? null
            : String.join(",", configurations));
    }

    /**
     *
     * This is equivalent to getting the {@link Deployable#CONFIGURATION_CLASSES} property.
     * @return The configuration class names.
     */
    @ManagedAttribute("configuration classes for webapps to be processed through")
    public String[] getConfigurationClasses()
    {
        String cc = _properties.get(Deployable.CONFIGURATION_CLASSES);
        return cc == null ? new String[0] : cc.split(",");
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to setting the {@link Deployable#BASE_TEMP_DIR} property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(String directory)
    {
        _properties.put(Deployable.BASE_TEMP_DIR, directory);
    }

    /**
     * Set the temporary directory for deployment.
     * <p>
     * This is equivalent to setting the {@link Deployable#BASE_TEMP_DIR} property.
     * If not set, then the <code>java.io.tmpdir</code> System Property is used.
     *
     * @param directory the new work directory
     */
    public void setTempDir(File directory)
    {
        _properties.put(Deployable.BASE_TEMP_DIR, directory.getAbsolutePath());
    }

    /**
     * Get the temporary directory for deployment.
     * <p>
     * This is equivalent to getting the {@link Deployable#BASE_TEMP_DIR} property.
     *
     * @return the user supplied work directory (null if user has not set Temp Directory yet)
     */
    @ManagedAttribute("temp directory for use, null if no user set temp directory")
    public File getTempDir()
    {
        String tmpDir = _properties.get(Deployable.BASE_TEMP_DIR);
        return tmpDir == null ? null : new File(tmpDir);
    }

    protected ContextHandler initializeContextHandler(Object context, Path path, Map<String, String> properties)
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
            if (LOG.isDebugEnabled())
                LOG.debug("Not a context {}", context);
            
            return null;
        }

        assert contextHandler != null;

        initializeContextPath(contextHandler, path);

        if (Files.isDirectory(path))
            contextHandler.setBaseResource(Resource.newResource(path));

        //TODO think of better way of doing this
        //pass through properties as attributes directly
        for (Map.Entry<String, String> prop : properties.entrySet())
        {
            String key = prop.getKey();
            String value = prop.getValue();
            if (key.startsWith(Deployable.ATTRIBUTE_PREFIX))
                contextHandler.setAttribute(key.substring(Deployable.ATTRIBUTE_PREFIX.length()), value);
        }

        String contextPath = properties.get(Deployable.CONTEXT_PATH);
        if (StringUtil.isNotBlank(contextPath))
            contextHandler.setContextPath(contextPath);

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
            Path path = app.getPath().toRealPath().toAbsolutePath().toFile().getCanonicalFile().toPath();
            if (!Files.exists(path))
                throw new IllegalStateException("App resource does not exist " + path);

            // prepare properties
            Map<String, String> properties = new HashMap<>();
            properties.putAll(getProperties());
            properties.putAll(app.getProperties());

            // Handle a context XML file
            if (FileID.isXml(path))
            {
                XmlConfiguration xmlc = new XmlConfiguration(path, null, properties)
                {
                    @Override
                    public void initializeDefaults(Object context)
                    {
                        super.initializeDefaults(context);
                        ContextProvider.this.initializeContextHandler(context, path, properties);
                    }
                };

                xmlc.getIdMap().put("Environment", environment);
                xmlc.setJettyStandardIdsAndProperties(getDeploymentManager().getServer(), path);

                ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    // If it is a core context environment, then look for a classloader
                    ClassLoader coreContextClassLoader = Environment.CORE.equals(environment)
                        ? findCoreContextClassLoader(path) : null;
                    if (coreContextClassLoader != null)
                        Thread.currentThread().setContextClassLoader(coreContextClassLoader);

                    // Create the context by running the configuration
                    Object context = xmlc.configure();

                    // Look for the contextHandler itself
                    ContextHandler contextHandler = null;
                    if (context instanceof ContextHandler c)
                        contextHandler = c;
                    else if (context instanceof Supplier<?> supplier)
                    {
                        Object nestedContext = supplier.get();
                        if (nestedContext instanceof ContextHandler c)
                            contextHandler = c;
                    }
                    if (contextHandler == null)
                        throw new IllegalStateException("Unknown context type of " + context);

                    // Set the classloader if we have a coreContextClassLoader
                    if (coreContextClassLoader != null)
                        contextHandler.setClassLoader(coreContextClassLoader);

                    return contextHandler;
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(oldLoader);
                }
            }
            // Otherwise it must be a directory or an archive
            else if (!Files.isDirectory(path) && !FileID.isWebArchive(path))
            {
                throw new IllegalStateException("unable to create ContextHandler for " + app);
            }

            // Build the web application
            String contextHandlerClassName = (String)environment.getAttribute("contextHandlerClass");
            if (StringUtil.isBlank(contextHandlerClassName))
                throw new IllegalStateException("No ContextHandler classname for " + app);
            Class<?> contextHandlerClass = Loader.loadClass(contextHandlerClassName);
            if (contextHandlerClass == null)
                throw new IllegalStateException("Unknown ContextHandler class " + contextHandlerClassName + " for " + app);

            Object context = contextHandlerClass.getDeclaredConstructor().newInstance();
            properties.put(Deployable.WAR, path.toString());
            return initializeContextHandler(context, path, properties);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    protected ClassLoader findCoreContextClassLoader(Path path) throws MalformedURLException, IOException
    {
        String basename = FileID.getBasename(path);
        List<URL> urls = new ArrayList<>();

        // Is there a matching jar file?
        Path contextJar = path.resolve(basename + ".jar");
        if (!Files.exists(contextJar))
            contextJar = path.resolve(basename + ".JAR");
        if (Files.exists(contextJar))
            urls.add(contextJar.toUri().toURL());

        // Is there a matching lib directory?
        Path libDir = path.resolve(basename + ".d" + path.getFileSystem().getSeparator() + "lib");
        if (Files.exists(libDir) && Files.isDirectory(libDir))
        {
            try (Stream<Path> paths = Files.list(libDir))
            {
                paths.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .map(Path::toUri)
                    .forEach(uri ->
                    {
                        try
                        {
                            urls.add(uri.toURL());
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }

        // Is there a matching lib directory?
        Path classesDir = path.resolve(basename + ".d" + path.getFileSystem().getSeparator() + "classes");
        if (Files.exists(classesDir) && Files.isDirectory(libDir))
            urls.add(classesDir.toUri().toURL());

        if (urls.isEmpty())
            return null;
        return new URLClassLoader(urls.toArray(new URL[0]));
    }

    protected void initializeContextPath(ContextHandler context, Path path)
    {
        // Strip any 3 char extension from non directories
        String basename = FileID.getBasename(path);
        String contextPath = basename;

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
        context.setDisplayName(basename);
        context.setContextPath(contextPath);
    }

    protected boolean isDeployable(Path path)
    {
        String basename = FileID.getBasename(path);

        //is the file that changed a directory?
        if (Files.isDirectory(path))
        {
            // deploy if there is not a .xml or .war file of the same basename?
            return !Files.exists(path.getParent().resolve(basename + ".xml")) &&
                !Files.exists(path.getParent().resolve(basename + ".XML")) &&
                !Files.exists(path.getParent().resolve(basename + ".war")) &&
                !Files.exists(path.getParent().resolve(basename + ".WAR"));
        }

        // deploy if there is not a .war for of the same basename
        if (FileID.isWebArchive(path))
        {
            // if a .xml file exists for it
            return !Files.exists(path.getParent().resolve(basename + ".xml")) &&
                !Files.exists(path.getParent().resolve(basename + ".XML"));
        }

        // otherwise only deploy an XML
        return FileID.isXml(path);
    }

    @Override
    protected void pathChanged(Path path) throws Exception
    {
        if (Files.exists(path) && isDeployable(path))
            super.pathChanged(path);
    }

    @Override
    protected void pathAdded(Path path) throws Exception
    {
        if (Files.exists(path) && isDeployable(path))
            super.pathAdded(path);
    }

    @Override
    protected void pathRemoved(Path path) throws Exception
    {
        if (isDeployable(path))
            super.pathRemoved(path);
    }
}
