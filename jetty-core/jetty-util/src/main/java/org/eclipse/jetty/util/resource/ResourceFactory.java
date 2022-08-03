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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ResourceFactory.
 */
public interface ResourceFactory
{
    Logger LOG = LoggerFactory.getLogger(Resource.class);

    /**
     * Construct a system resource from a string.
     * The resource is tried as classloader resource before being
     * treated as a normal resource.
     *
     * @param resource Resource as string representation
     * @return The new Resource
     */
    default Resource newSystemResource(String resource)
    {
        URL url = null;
        // Try to format as a URL?
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null)
        {
            try
            {
                url = loader.getResource(resource);
                if (url == null && resource.startsWith("/"))
                    url = loader.getResource(resource.substring(1));
            }
            catch (IllegalArgumentException e)
            {
                LOG.trace("IGNORED", e);
                // Catches scenario where a bad Windows path like "C:\dev" is
                // improperly escaped, which various downstream classloaders
                // tend to have a problem with
                url = null;
            }
        }
        if (url == null)
        {
            loader = Resource.class.getClassLoader();
            if (loader != null)
            {
                url = loader.getResource(resource);
                if (url == null && resource.startsWith("/"))
                    url = loader.getResource(resource.substring(1));
            }
        }

        if (url == null)
        {
            url = ClassLoader.getSystemResource(resource);
            if (url == null && resource.startsWith("/"))
                url = ClassLoader.getSystemResource(resource.substring(1));
        }

        if (url == null)
            return null;

        try
        {
            URI uri = url.toURI();
            return newResource(uri);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("Error creating resource from URL: " + url, e);
        }
    }

    /**
     * Find a classpath resource.
     * The {@link Class#getResource(String)} method is used to lookup the resource. If it is not
     * found, then the {@link Loader#getResource(String)} method is used.
     * If it is still not found, then {@link ClassLoader#getSystemResource(String)} is used.
     * Unlike {@link ClassLoader#getSystemResource(String)} this method does not check for normal resources.
     *
     * @param resource the relative name of the resource
     * @return Resource or null
     */
    default Resource newClassPathResource(String resource)
    {
        URL url = Resource.class.getResource(resource);

        if (url == null)
            url = Loader.getResource(resource);
        if (url == null)
            return null;
        try
        {
            URI uri = url.toURI();
            return Resource.createResource(uri);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    Resource newResource(URI uri);

    default Resource newResource(String resource)
    {
        return newResource(Resource.toURI(resource));
    }

    default Resource newResource(Path path)
    {
        return newResource(path.toUri());
    }

    default ResourceCollection newResource(List<URI> uris)
    {
        return new ResourceCollection(uris.stream().map(this::newResource).toList());
    }

    default Resource newResource(URL url)
    {
        try
        {
            return newResource(url.toURI());
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("Error creating resource from URL: " + url, e);
        }
    }

    default Resource newJarFileResource(URI uri)
    {
        if (!FileID.isArchive(uri))
            throw new IllegalArgumentException("Path is not a Java Archive: " + uri);
        if (!uri.getScheme().equalsIgnoreCase("file"))
            throw new IllegalArgumentException("Not an allowed path: " + uri);
        return newResource(URIUtil.toJarFileUri(uri));
    }

    static ResourceFactory of(Resource baseResource)
    {
        Objects.requireNonNull(baseResource);

        if (baseResource instanceof ResourceFactory resourceFactory)
            return resourceFactory;

        return new ResourceFactory()
        {
            @Override
            public Resource newResource(URI resource)
            {
                // TODO add an optimized pathway that keeps the URI and doesn't go via String
                return newResource(resource.toString());
            }

            @Override
            public Resource newResource(String resource)
            {
                return baseResource.resolve(resource);
            }
        };
    }

    static ResourceFactory.Closeable closeable()
    {
        return new Closeable();
    }

    static ResourceFactory.ContainerResourceFactory container()
    {
        ContainerResourceFactory factory = new ContainerResourceFactory();
        LifeCycle.start(factory);
        return factory;
    }

    static ResourceFactory of(Container container)
    {
        Objects.requireNonNull(container);

        ContainerResourceFactory factory = container.getBean(ContainerResourceFactory.class);
        if (factory == null)
        {
            factory = container();
            container.addBean(factory, true);
        }
        return factory;
    }

    static ResourceFactory root()
    {
        return __ROOT;
    }

    // TODO move somewhere else as a private field
    ResourceFactory __ROOT = new ResourceFactory()
    {
        @Override
        public Resource newResource(URI uri)
        {
            Resource.Mount mount = Resource.mountIfNeeded(uri);
            if (mount != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("Leaked {} for {}", mount, uri, new Throwable());
                else
                    LOG.warn("Leaked {} for {}", mount, uri);
            }
            return Resource.createResource(uri);
        }

        @Override
        public Resource newResource(String resource)
        {
            return newResource(Resource.toURI(resource));
        }
    };

    // TODO make this an interface and move impl somewhere private
    class Closeable implements ResourceFactory, java.io.Closeable
    {
        private final List<Resource.Mount> _mounts = new CopyOnWriteArrayList<>();

        @Override
        public Resource newResource(URI uri)
        {
            Resource.Mount mount = Resource.mountIfNeeded(uri);
            if (mount != null)
                _mounts.add(mount);
            return Resource.createResource(uri);
        }

        @Override
        public void close()
        {
            for (Resource.Mount mount : _mounts)
                IO.close(mount);
            _mounts.clear();
        }
    }

    // TODO make this an interface and move impl somewhere private
    class ContainerResourceFactory extends AbstractLifeCycle implements ResourceFactory, Dumpable
    {
        private final List<Resource.Mount> _mounts = new CopyOnWriteArrayList<>();

        @Override
        public Resource newResource(URI uri)
        {
            Resource.Mount mount = Resource.mountIfNeeded(uri);
            if (mount != null)
                _mounts.add(mount);
            return Resource.createResource(uri);
        }

        @Override
        protected void doStop() throws Exception
        {
            for (Resource.Mount mount : _mounts)
                IO.close(mount);
            _mounts.clear();
            super.doStop();
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent, this, new DumpableCollection("mounts", _mounts));
        }
    }
}
