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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A classloader isolated Core WebApp.
 *
 * <p>
 * The Base Resource represents the metadata base that defines this {@code CoreContextHandler}.
 * </p>
 * <p>
 * The metadata base can be a directory on disk, or a non-traditional {@code war} file with the following contents.
 * </p>
 * <ul>
 *     <li>{@code <metadata>/lib/*.jar} - the jar files for the classloader of this webapp</li>
 *     <li>{@code <metadata>/classes/} - the raw class files for this webapp</li>
 *     <li>{@code <metadata>/static/} - the static content to serve for this webapp</li>
 * </ul>
 * <p>
 *     Note: if using the non-traditional {@code war} as your metadata base, the
 *     existence of {@code <metadata>/lib/*.jar} files means the archive will be
 *     unpacked into the temp directory defined by this core webapp.
 * </p>
 */
public class CoreContextHandler extends ContextHandler implements Deployable
{
    private static final Logger LOG = LoggerFactory.getLogger(CoreContextHandler.class);
    private static final String ORIGINAL_BASE_RESOURCE = "org.eclipse.jetty.webapp.originalBaseResource";
    private boolean _initialized = false;
    private List<Resource> _extraClasspath;
    private boolean _builtClassLoader = false;

    public CoreContextHandler()
    {
        this("/");
    }

    public CoreContextHandler(String contextPath)
    {
        super();
        setContextPath(contextPath);
    }

    /**
     * @return List of Resources that each will represent a new classpath entry
     */
    @ManagedAttribute(value = "extra classpath for context classloader", readonly = true)
    public List<Resource> getExtraClasspath()
    {
        return _extraClasspath == null ? Collections.emptyList() : _extraClasspath;
    }

    @Override
    public void initializeDefaults(Attributes attributes)
    {
        try
        {
            boolean nominatedDirDefined = false;

            // This CoreContextHandler is arriving via a Deployer
            for (String keyName : attributes.getAttributeNameSet())
            {
                Object value = attributes.getAttribute(keyName);

                switch (keyName)
                {
                    case Deployable.CONTEXT_PATH, Deployable.DEFAULT_CONTEXT_PATH -> setContextPath((String)value);
                    case Deployable.OTHER_PATHS ->
                    {
                        // The Base Resource (before init) is a nominated directory ("/<name>.d/")

                        //noinspection unchecked
                        java.util.Collection<Path> deployablePaths = (java.util.Collection<Path>)value;
                        List<Path> nominatedDirs = deployablePaths.stream()
                            .filter(Files::isDirectory)
                            .filter(p -> FileID.isExtension(p.getFileName().toString(), "d"))
                            .sorted(PathCollators.byName(true))
                            .toList();

                        if (nominatedDirs.size() == 1)
                        {
                            ResourceFactory resourceFactory = ResourceFactory.of(this);
                            Resource resourceDir = resourceFactory.newResource(nominatedDirs.get(0));
                            setBaseResource(resourceDir);
                            nominatedDirDefined = true;
                        }
                        else if (nominatedDirs.size() > 1)
                        {
                            /* this is possible on filesystems that allow things like:
                             *
                             * webapps/foo.d
                             * webapps/FOO.D
                             * webapps/FOO.d
                             *
                             * We want to only support 1 directory in this case.
                             */
                            throw new IllegalArgumentException("More than one nominated directory is not supported: " +
                                nominatedDirs.stream().map(Path::toString).collect(Collectors.joining(", ", "[", "]")));
                        }

                        // Normal directories.
                        List<Path> dirs = deployablePaths.stream()
                            .filter(Files::isDirectory)
                            .filter(p -> !FileID.isExtension(p.getFileName().toString(), "d"))
                            .sorted(PathCollators.byName(true))
                            .toList();
                        if (!nominatedDirs.isEmpty() && !dirs.isEmpty())
                        {
                            throw new IllegalArgumentException("Unsupported multiple deployment directories: " +
                                Stream.concat(nominatedDirs.stream(), dirs.stream()).map(Path::toString).collect(Collectors.joining(", ", "[", "]")));
                        }
                        else if (dirs.size() == 1)
                        {
                            ResourceFactory resourceFactory = ResourceFactory.of(this);
                            Resource resourceDir = resourceFactory.newResource(dirs.get(0));
                            setBaseResource(resourceDir);
                        }
                        else if (dirs.size() > 1)
                        {
                            throw new IllegalArgumentException("More than one deployment directory is not supported: " +
                                dirs.stream().map(Path::toString).collect(Collectors.joining(", ", "[", "]")));
                        }
                    }
                }
            }

            // Init the webapp, unpack if necessary, create the classloader, etc.
            if (nominatedDirDefined)
                initWebApp();
            else
                initStaticWebApp();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException("Unable to init " + this.getClass().getSimpleName(), e);
        }
    }

    /**
     * Set the Extra ClassPath via delimited String.
     * <p>
     * This is a convenience method for {@link #setExtraClasspath(List)}
     * </p>
     *
     * @param extraClasspath Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     * @see #setExtraClasspath(List)
     */
    public void setExtraClasspath(String extraClasspath)
    {
        setExtraClasspath(getResourceFactory().split(extraClasspath));
    }

    public void setExtraClasspath(List<Resource> extraClasspath)
    {
        _extraClasspath = extraClasspath;
    }

    public ResourceFactory getResourceFactory()
    {
        return ResourceFactory.of(this);
    }

    protected Resource unpack(Resource dir) throws IOException
    {
        Path tempDir = getTempDirectory().toPath();
        dir.copyTo(tempDir);
        return ResourceFactory.of(this).newResource(tempDir);
    }

    protected ClassLoader newClassLoader(Resource base) throws IOException
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

        List<Resource> extraEntries = getExtraClasspath();
        if (extraEntries != null && !extraEntries.isEmpty())
        {
            for (Resource entry : extraEntries)
            {
                urls.add(entry.getURI().toURL());
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Core classloader for {}", urls);

        if (urls.isEmpty())
            return Environment.CORE.getClassLoader();

        return new URLClassLoader(urls.toArray(URL[]::new), Environment.CORE.getClassLoader());
    }

    protected void initWebApp() throws IOException
    {
        if (_initialized)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Already initialized, not initializing again");
            return;
        }

        if (getBaseResource() == null)
        {
            // Nothing to do.
            return;
        }

        Resource base = getBaseResource();

        if (!Resources.isDirectory(base))
        {
            // see if we can unpack this reference.
            if (FileID.isExtension(base.getURI(), "zip", "jar", "war"))
            {
                // We have an archive that needs to be unpacked
                setAttribute(ORIGINAL_BASE_RESOURCE, base.getURI());
                try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
                {
                    URI archiveURI = URIUtil.toJarFileUri(base.getURI());
                    Resource mountedArchive = resourceFactory.newResource(archiveURI);
                    base = unpack(mountedArchive);
                    setBaseResource(base);
                }
            }
            else
            {
                throw new IllegalArgumentException("Unrecognized non-directory base resource type: " + base);
            }
        }

        _initialized = true;

        Resource staticDir = base.resolve("static");
        if (Resources.isDirectory(staticDir))
        {
            if (!isResourceHandlerAlreadyPresent(staticDir))
            {
                ResourceHandler resourceHandler = new ResourceHandler();
                resourceHandler.setBaseResource(staticDir);
                setHandler(resourceHandler);
            }
        }

        // Don't override the user provided ClassLoader
        if (getClassLoader() == null)
        {
            _builtClassLoader = true;
            setClassLoader(newClassLoader(getBaseResource()));
        }
    }

    protected void initStaticWebApp() throws IOException
    {
        if (_initialized)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Already initialized, not initializing again");
            return;
        }

        if (getBaseResource() == null)
        {
            // Nothing to do.
            return;
        }

        Resource staticDir = getBaseResource();

        _initialized = true;

        if (Resources.isDirectory(staticDir))
        {
            if (!isResourceHandlerAlreadyPresent(staticDir))
            {
                ResourceHandler resourceHandler = new ResourceHandler();
                resourceHandler.setBaseResource(staticDir);
                setHandler(resourceHandler);
            }
        }
    }

    private boolean isResourceHandlerAlreadyPresent(Resource staticDir)
    {
        boolean alreadyExists = false;
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
                        alreadyExists = true;
                    }
                }
            }
        }
        return alreadyExists;
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
        if (_builtClassLoader)
        {
            setClassLoader(null);
            _builtClassLoader = false;
        }

        _initialized = false;

        super.doStop();
    }
}
