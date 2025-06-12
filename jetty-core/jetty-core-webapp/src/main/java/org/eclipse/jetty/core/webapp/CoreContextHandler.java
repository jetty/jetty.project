//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.webapp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Core WebApp.
 *
 * <p>
 * The Base Resource represents the metadata base that defines this {@code CoreContextHandler}.
 * </p>
 * <p>
 * The metadata base can be a directory on disk, or an archive file (supporting {@code jar}, {@code zip}, and {@code war}) with the following contents.
 * </p>
 * <ul>
 *     <li>{@code <metadata>/lib/*.jar} - the jar files for the classloader of this webapp</li>
 *     <li>{@code <metadata>/classes/} - the raw class files for this webapp</li>
 *     <li>{@code <metadata>/static/} - the static content to serve for this webapp</li>
 * </ul>
 * <p>
 *     Note: if using the archive file as your metadata base, the
 *     existence of {@code <metadata>/lib/*.jar} files means the archive will be
 *     unpacked into the temp directory defined by this core webapp.
 * </p>
 */
public class CoreContextHandler extends ContextHandler implements Deployable
{
    private static final Logger LOG = LoggerFactory.getLogger(CoreContextHandler.class);
    private static final String ORIGINAL_BASE_RESOURCE_ATTRIBUTE = "jetty.deploy.core.originalBaseResource";
    private static final String EXTRA_CLASS_PATH_ATTRIBUTE = "jetty.deploy.core.extraClassPath";
    private static final String JETTY_WEB_XML = "jetty-web.xml";
    private boolean initialized = false;
    private List<Resource> extraClassPath;
    private ClassLoader previousClassLoader;
    private Boolean deferredDirAllowed;

    public CoreContextHandler()
    {
        this(null);
    }

    public CoreContextHandler(String contextPath)
    {
        // don't set contextPath if not provided, leave it at "default" of "/" (to maintain default-context-path behaviors)
        if (contextPath != null)
            setContextPath(contextPath);
    }

    /**
     * @return List of Resources that each will represent a new classpath entry
     */
    @ManagedAttribute(value = "extra classpath for context classloader", readonly = true)
    public List<Resource> getExtraClassPath()
    {
        return extraClassPath == null ? Collections.emptyList() : extraClassPath;
    }

    @Override
    protected void initializeDefault(String keyName, Object value)
    {
        switch (keyName)
        {
            case EXTRA_CLASS_PATH_ATTRIBUTE ->
            {
                if (value instanceof String str)
                    setExtraClassPath(str);
            }
            case Deployable.DIR_ALLOWED ->
            {
                if (value instanceof String str)
                    setDirAllowed(Boolean.parseBoolean(str));
                else if (value instanceof Boolean bool)
                    setDirAllowed(bool);
            }
            case Deployable.MAIN_PATH ->
            {
                // The Base Resource
                Path mainPath = (Path)value;
                if (Files.isDirectory(mainPath) || FileID.isArchive(mainPath))
                {
                    ResourceFactory resourceFactory = ResourceFactory.of(this);
                    Resource baseResource = resourceFactory.newResource((Path)value);
                    setBaseResource(baseResource);
                }
            }
            case Deployable.OTHER_PATHS ->
            {
                //noinspection unchecked
                java.util.Collection<Path> deployablePaths = (java.util.Collection<Path>)value;
                Path mainDir = null;

                for (Path path : deployablePaths)
                {
                    if (Files.isDirectory(path))
                    {
                        if (mainDir == null)
                        {
                            mainDir = path;
                        }
                        else
                        {
                            throw new IllegalArgumentException("More than one directory is not supported: " +
                                deployablePaths.stream().map(Path::toString).collect(Collectors.joining(", ", "[", "]")));
                        }
                    }
                }

                if (mainDir != null)
                {
                    // A single directory is the only form supported.
                    // The breakdown of this directory (classes/, lib/*.jar, static/) is done by initWebApp();
                    ResourceFactory resourceFactory = ResourceFactory.of(this);
                    Resource resourceDir = resourceFactory.newResource(mainDir);
                    setBaseResource(resourceDir);
                }
            }
        }
    }

    private void setDirAllowed(Boolean bool)
    {
        ResourceHandler resourceHandler = getBean(ResourceHandler.class);
        if (resourceHandler != null)
        {
            resourceHandler.setDirAllowed(bool);
        }
        else
        {
            deferredDirAllowed = bool;
        }
    }

    @Override
    protected void initializeDefaultsComplete()
    {
        // Init the webapp, unpack if necessary, create the classloader, etc.
        try
        {
            initWebApp();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Unable to init " + TypeUtil.toShortName(this.getClass()), e);
        }
    }

    /**
     * Set the Extra class path via delimited String.
     *
     * @param extraClasspath delimited path of filenames or URLs
     * pointing to directories or jar files. (see {@link ResourceFactory#split(String)} for behavior.)
     * @see #setExtraClassPath(List)
     * @see ResourceFactory#split(String)
     */
    public void setExtraClassPath(String extraClasspath)
    {
        setExtraClassPath(getResourceFactory().split(extraClasspath));
    }

    /**
     * Set Extra class path entries.
     *
     * @param extraClasspath the entries to add
     * @see #setExtraClassPath(List)
     */
    public void setExtraClassPath(String... extraClasspath)
    {
        setExtraClassPath(Stream.of(extraClasspath)
            .map(entry -> getResourceFactory().newResource(entry))
            .toList()
        );
    }

    /**
     * Set Extra class path entries.
     *
     * @param extraClasspath the entries to add
     * @see #setExtraClassPath(List)
     */
    public void setExtraClassPath(List<Resource> extraClasspath)
    {
        extraClassPath = extraClasspath;
    }

    public ResourceFactory getResourceFactory()
    {
        return ResourceFactory.of(this);
    }

    @Override
    public void setBaseResource(Resource baseResource)
    {
        if (baseResource == null || Resources.isDirectory(baseResource))
        {
            super.setBaseResource(baseResource);
            return;
        }

        if (Resources.isReadableFile(baseResource))
        {
            URI uri = baseResource.getURI();
            if (FileID.isArchive(uri))
            {
                // convert to "jar:file:" resource
                Resource jarResource = getResourceFactory().newJarFileResource(uri);
                super.setBaseResource(jarResource);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Ignored base resource: {}", baseResource);
            }
            return;
        }

        super.setBaseResource(baseResource);
    }

    protected Resource unpack(Resource dir) throws IOException
    {
        Path tempDir = getTempDirectory().toPath();
        dir.copyTo(tempDir);
        return ResourceFactory.of(this).newResource(tempDir);
    }

    /**
     * Create a ClassLoader from the baseResource.
     * @param baseResource the base resource
     * @param parentClassLoader the parent classloader
     * @return the new classloader
     */
    private ClassLoader newClassLoader(Resource baseResource, ClassLoader parentClassLoader) throws IOException
    {
        Attributes attributes = new Attributes.Mapped();
        attributes.setAttribute(TEMP_DIR, getTempDirectory());

        if (baseResource == null)
            return null;

        Objects.requireNonNull(attributes);

        if (!Resources.isDirectory(baseResource))
        {
            // see if we can unpack this reference.
            if (FileID.isArchive(baseResource.getURI()))
            {
                // We have an archive that needs to be unpacked
                attributes.setAttribute(ORIGINAL_BASE_RESOURCE_ATTRIBUTE, baseResource.getURI());

                URI archiveURI = URIUtil.toJarFileUri(baseResource.getURI());

                Resource mountedArchive = getResourceFactory().newResource(archiveURI);
                baseResource = unpack(mountedArchive);
                attributes.setAttribute(Deployable.BASE_RESOURCE, baseResource);
            }
            else
            {
                throw new IllegalArgumentException("Unrecognized non-directory base resource type: " + baseResource);
            }
        }

        List<URL> urls = findClassLoaderURLs(baseResource);
        if (urls.isEmpty())
            return null; // No custom ClassLoader is necessary

        if (LOG.isDebugEnabled())
            LOG.debug("Core webapp classloader: {}", urls);

        return new URLClassLoader(urls.toArray(URL[]::new), parentClassLoader);
    }

    private List<URL> findClassLoaderURLs(Resource baseResource) throws IOException
    {
        List<URL> urls = new ArrayList<>();

        if (Resources.isDirectory(baseResource))
        {
            Resource libDir = baseResource.resolve("lib");
            if (Resources.isDirectory(libDir))
            {
                for (Resource entry : libDir.list())
                {
                    URI uri = entry.getURI();
                    if (FileID.isJavaArchive(uri))
                        urls.add(uri.toURL());
                }
            }

            Resource classesDir = baseResource.resolve("classes");
            if (Resources.isDirectory(classesDir))
            {
                urls.add(classesDir.getURI().toURL());
            }
        }

        List<Resource> extraEntries = getExtraClassPath();
        if (extraEntries != null && !extraEntries.isEmpty())
        {
            for (Resource entry : extraEntries)
            {
                urls.add(entry.getURI().toURL());
            }
        }

        return urls;
    }

    protected void initWebApp() throws IOException
    {
        if (initialized)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Already initialized, not initializing again");
            return;
        }

        initialized = true;

        Resource baseResource = getBaseResource();
        if (baseResource == null)
            return;

        Resource staticDir = baseResource.resolve("static");
        if (Resources.isDirectory(staticDir))
        {
            if (!isResourceHandlerAlreadyPresent(staticDir))
            {
                ResourceHandler resourceHandler = new ResourceHandler();
                resourceHandler.setBaseResource(staticDir);
                if (deferredDirAllowed != null)
                    resourceHandler.setDirAllowed(deferredDirAllowed);
                setHandler(resourceHandler);
            }
        }

        Environment environment = Environment.get("core");
        if (environment == null)
            throw new IllegalStateException("Could not find environment [core]");

        // Don't override the user provided ClassLoader.
        ClassLoader classLoader = getClassLoader();
        previousClassLoader = classLoader;
        if (classLoader == null)
            classLoader = environment.getClassLoader();
        setClassLoader(newClassLoader(baseResource, classLoader));

        // Load internal Jetty XML to self-configure, if present
        Resource xmlFile = baseResource.resolve(JETTY_WEB_XML);
        if (Resources.isReadableFile(xmlFile))
        {
            applyXml(xmlFile, environment);
        }
    }

    private void applyXml(Resource xmlFile, Environment environment)
    {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader classLoader = getClassLoader();
        if (classLoader == null)
            classLoader = environment.getClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(classLoader);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(xmlFile, null, asProperties(this));
            xmlConfiguration.getIdMap().put("Environment", environment.getName());
            xmlConfiguration.setJettyStandardIdsAndProperties(getServer(), xmlFile.getPath());

            // Put all Environment attributes into XmlConfiguration as properties that can be used.
            getAttributeNameSet()
                .stream()
                .filter(k -> !k.startsWith("jetty.home") &&
                    !k.startsWith("jetty.base") &&
                    !k.startsWith("jetty.webapps"))
                .forEach(k ->
                {
                    Object v = getAttribute(k);
                    if (v == null)
                        xmlConfiguration.getProperties().remove(k);
                    else
                        xmlConfiguration.getProperties().put(k, asPropertyValue(v));
                });

            // Configure this context
            xmlConfiguration.configure(this);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private static Map<String, String> asProperties(Attributes attributes)
    {
        Map<String, String> props = new HashMap<>();
        attributes.getAttributeNameSet().forEach((name) ->
        {
            Object value = attributes.getAttribute(name);
            props.put(name, Objects.toString(value));
        });
        return props;
    }

    /**
     * Convert an Object into a String suitable for use as a Properties value.
     *
     * @param obj the object to convert
     * @return the String representing the Object, or null if {@code obj} is null.
     */
    private static String asPropertyValue(Object obj)
    {
        if (obj == null)
            return null;
        if (obj instanceof Enum<?> en)
            return en.name();
        if (obj instanceof Environment env)
            return env.getName();
        return Objects.toString(obj);
    }

    private boolean isResourceHandlerAlreadyPresent(Resource staticDir)
    {
        for (Handler handler : getHandlers())
        {
            if (handler instanceof ResourceHandler resourceHandler)
            {
                Resource baseResource = resourceHandler.getBaseResource();
                if (baseResource != null)
                {
                    URI baseResourceURI = baseResource.getURI();
                    if (baseResourceURI.equals(staticDir.getURI()))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected void doStart() throws Exception
    {
        initWebApp();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        initialized = false;
        setClassLoader(previousClassLoader);
        previousClassLoader = null;
        super.doStop();
    }
}
