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

package org.eclipse.jetty.server.handler;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
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
    private static final String ORIGINAL_BASE_RESOURCE = "org.eclipse.jetty.core.originalBaseResource";
    private static final String EXTRA_CLASSPATH = "org.eclipse.jetty.core.extraClassPath";
    private static final String CLASSLOADER_RESOURCE_FACTORY = "org.eclipse.jetty.core.classloaderResourceFactory";
    private boolean _initialized = false;
    // The ResourceFactory in use by the ClassLoader
    private ResourceFactory.LifeCycle _classLoaderResourceFactory;
    private ClassLoader _previousClassLoader;
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

    @Override
    protected void doStart() throws Exception
    {
        initWebApp();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _initialized = false;
        setClassLoader(_previousClassLoader);
        _previousClassLoader = null;
        super.doStop();
        if (_classLoaderResourceFactory != null)
            removeBean(_classLoaderResourceFactory);
    }

    protected void initWebApp() throws IOException
    {
        if (_initialized)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Already initialized, not initializing again");
            return;
        }

        _initialized = true;

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
        _previousClassLoader = classLoader;
        if (classLoader == null)
            classLoader = environment.getClassLoader();
        setClassLoader(newClassLoader(baseResource, classLoader));
    }

    @Override
    protected void initializeDefault(String keyName, Object value)
    {
        switch (keyName)
        {
            case CLASSLOADER_RESOURCE_FACTORY ->
            {
                // Grab the ResourceFactory that was created by CoreContextClassLoaderFactory for lifecycle reasons.
                _classLoaderResourceFactory = (ResourceFactory.LifeCycle)value;
                addManaged(_classLoaderResourceFactory);
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

    protected Resource unpack(Resource dir) throws IOException
    {
        Path tempDir = getTempDirectory().toPath();
        dir.copyTo(tempDir);
        return ResourceFactory.of(this).newResource(tempDir);
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
        CoreContextClassLoaderFactory classLoaderFactory = new CoreContextClassLoaderFactory();

        return classLoaderFactory.newClassLoader(attributes,
            ResourceFactory.of(this),
            baseResource,
            parentClassLoader);
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

    /**
     * <p>
     * {@link org.eclipse.jetty.server.Deployable.ClassLoaderFactory} responsible for creating a {@link ClassLoader}
     * that is suitable for a {@link CoreContextHandler}.
     * </p>
     *
     * <p>
     *     This will be a {@link URLClassLoader} that has the following entries.
     * </p>
     * <dl>
     *     <dt>{@code ${baseResource}/lib/*.jar}</dt>
     *     <dd>(optional) every JAR file found in the lib directory will become an entry in the resulting {@link URLClassLoader}</dd>
     *     <dt>{@code ${baseResource}/classes/}</dt>
     *     <dd>(optional) the classes directory is its own entry on the resulting {@link URLClassLoader}</dd>
     *     <dt>{@code ${extraClasspath}}</dt>
     *     <dd>(optional) the each entry in the extraClasspath will be its own entry on the resulting {@link URLClassLoader}</dd>
     * </dl>
     *
     * <p>
     *     The {@code ${baseResource}} can be either a directory, or an archive file.
     * </p>
     * <p>
     *     The {@code ${extraClasspath}} is an arbitrary list of extra classpath entries that are not present in the base resource.
     * </p>
     */
    public static class CoreContextClassLoaderFactory implements Deployable.ClassLoaderFactory
    {
        /**
         * Entry point from a tool that uses {@link Deployable}, information comes from deployable Attributes.
         *
         * @param attributes the deployable attributes
         * @return the ClassLoader
         * @throws IOException if unable to create the classloader
         */
        @Override
        public ClassLoader newClassLoader(Attributes attributes, Environment environment) throws IOException
        {
            // Create temporary ResourceFactory
            Path mainPath = findMainPath(attributes);
            if (mainPath == null)
                return null;

            ResourceFactory.LifeCycle resourceFactory = ResourceFactory.lifecycle();
            attributes.setAttribute(CLASSLOADER_RESOURCE_FACTORY, resourceFactory);
            Resource baseResource = resourceFactory.newResource(mainPath);
            return newClassLoader(attributes, resourceFactory, baseResource, environment.getClassLoader());
        }

        /**
         * Create a new ClassLoader.
         *
         * <p>
         *     The Deployable {@link Attributes} supported keys.
         * </p>
         * <dl>
         *     <dt>{@link Deployable#TEMP_DIR TEMP_DIR}</dt>
         *     <dd>(optional) - points to a valid temp directory (whatever {@link IO#asFile(Object)} supports)</dd>
         *     <dt>{@link #EXTRA_CLASSPATH}</dt>
         *     <dd>(optional) - delimited string of classpath entries (whatever {@link ResourceFactory#split(String)} supports)</dd>
         * </dl>
         *
         * @param attributes the deployable attributes.
         * @param resourceFactory the resource factory to base any new Resource's from.
         * @param baseResource the base resource for this classloader.
         * @param parent the parent classloader.
         * @return the new classloader.
         * @throws IOException if unable to create classloader.
         */
        protected ClassLoader newClassLoader(Attributes attributes, ResourceFactory resourceFactory, Resource baseResource, ClassLoader parent) throws IOException
        {
            if (baseResource == null)
                return null;

            Objects.requireNonNull(attributes);
            Objects.requireNonNull(resourceFactory);
            Objects.requireNonNull(parent);

            if (!Resources.isDirectory(baseResource))
            {
                // see if we can unpack this reference.
                if (FileID.isArchive(baseResource.getURI()))
                {
                    // We have an archive that needs to be unpacked
                    attributes.setAttribute(ORIGINAL_BASE_RESOURCE, baseResource.getURI());

                    URI archiveURI = URIUtil.toJarFileUri(baseResource.getURI());
                    Resource mountedArchive = resourceFactory.newResource(archiveURI);
                    baseResource = unpack(attributes, resourceFactory, mountedArchive);
                    attributes.setAttribute(Deployable.BASE_RESOURCE, baseResource);
                }
                else
                {
                    throw new IllegalArgumentException("Unrecognized non-directory base resource type: " + baseResource);
                }
            }

            List<URL> urls = findClassLoaderURLs(resourceFactory, attributes, baseResource);
            if (urls.isEmpty())
                return null; // No custom ClassLoader is necessary

            if (LOG.isDebugEnabled())
                LOG.debug("Core webapp classloader: {}", urls);

            return new URLClassLoader(urls.toArray(URL[]::new), parent);
        }

        private Path findMainPath(Attributes attributes)
        {
            Path mainPath = null;

            for (String keyName : attributes.getAttributeNameSet())
            {
                switch (keyName)
                {
                    case Deployable.MAIN_PATH ->
                    {
                        Path path = (Path)attributes.getAttribute(keyName);
                        if (Files.isDirectory(path) || FileID.isArchive(path))
                        {
                            mainPath = path;
                        }
                    }
                    case Deployable.OTHER_PATHS ->
                    {
                        //noinspection unchecked
                        java.util.Collection<Path> deployablePaths = (java.util.Collection<Path>)attributes.getAttribute(Deployable.OTHER_PATHS);

                        for (Path path : deployablePaths)
                        {
                            if (Files.isDirectory(path))
                            {
                                if (mainPath == null)
                                {
                                    mainPath = path;
                                }
                                else
                                {
                                    throw new IllegalArgumentException("More than one directory is not supported: " +
                                        deployablePaths.stream().map(Path::toString).collect(Collectors.joining(", ", "[", "]")));
                                }
                            }
                        }
                    }
                }
            }

            return mainPath;
        }

        private List<Resource> getExtraClasspath(ResourceFactory resourceFactory, Attributes attributes)
        {
            Object extraClassPath = attributes.getAttribute(EXTRA_CLASSPATH);
            if (extraClassPath == null)
                return List.of();

            if (extraClassPath instanceof String extraClasspathStr)
            {
                return resourceFactory.split(extraClasspathStr);
            }

            throw new IllegalArgumentException("Unrecognized type (%s) on attribute %s"
                .formatted(extraClassPath.getClass().getName(), EXTRA_CLASSPATH));
        }

        private Path getTempDirectory(Attributes attributes) throws IOException
        {
            File tempDir = IO.asFile(attributes.getAttribute(Deployable.TEMP_DIR));
            if (tempDir != null)
                return tempDir.toPath();

            return Files.createTempDirectory("core-context");
        }

        private List<URL> findClassLoaderURLs(ResourceFactory resourceFactory, Attributes attributes, Resource base) throws IOException
        {
            List<URL> urls = new ArrayList<>();

            if (Resources.isDirectory(base))
            {
                Resource libDir = base.resolve("lib");
                if (Resources.isDirectory(libDir))
                {
                    for (Resource entry : libDir.list())
                    {
                        URI uri = entry.getURI();
                        if (FileID.isJavaArchive(uri))
                            urls.add(uri.toURL());
                    }
                }

                Resource classesDir = base.resolve("classes");
                if (Resources.isDirectory(classesDir))
                {
                    urls.add(classesDir.getURI().toURL());
                }
            }

            List<Resource> extraEntries = getExtraClasspath(resourceFactory, attributes);
            if (extraEntries != null && !extraEntries.isEmpty())
            {
                for (Resource entry : extraEntries)
                {
                    urls.add(entry.getURI().toURL());
                }
            }

            return urls;
        }

        private Resource unpack(Attributes attributes, ResourceFactory resourceFactory, Resource dir) throws IOException
        {
            Path tempDir = getTempDirectory(attributes);
            dir.copyTo(tempDir);
            return resourceFactory.newResource(tempDir);
        }
    }
}
