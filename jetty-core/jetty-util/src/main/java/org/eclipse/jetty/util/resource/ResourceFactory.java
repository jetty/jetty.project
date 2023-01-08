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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * ResourceFactory.
 */
public interface ResourceFactory
{
    /**
     * <p>Make a directory Resource containing a collection of other directory {@link Resource}s</p>
     * @param resources multiple directory {@link Resource}s to combine as a single resource. Order is significant.
     * @return A {@link CombinedResource} for multiple resources;
     *         or a single {@link Resource} if only 1 is passed;
     *         or null if none are passed.
     *         The returned {@link Resource} will always return {@code true} from {@link Resource#isDirectory()}
     * @throws IllegalArgumentException if a non-directory resource is passed.
     * @see CombinedResource
     */
    static Resource combine(List<Resource> resources)
    {
        return CombinedResource.combine(resources);
    }

    /**
     * <p>Make a directory Resource containing a collection of directory {@link Resource}s</p>
     * @param resources multiple directory {@link Resource}s to combine as a single resource. Order is significant.
     * @return A {@link CombinedResource} for multiple resources;
     *         or a single {@link Resource} if only 1 is passed;
     *         or null if none are passed.
     *         The returned {@link Resource} will always return {@code true} from {@link Resource#isDirectory()}
     * @throws IllegalArgumentException if a non-directory resource is passed.
     * @see CombinedResource
     */
    static Resource combine(Resource... resources)
    {
        return CombinedResource.combine(List.of(resources));
    }

    /**
     * Construct a resource from a uri.
     *
     * @param uri A URI.
     * @return A Resource object.
     */
    Resource newResource(URI uri);

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
        if (StringUtil.isBlank(resource))
            throw new IllegalArgumentException("Resource String is invalid: " + resource);

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
                // Catches scenario where a bad Windows path like "C:\dev" is
                // improperly escaped, which various downstream classloaders
                // tend to have a problem with
            }
        }

        if (url == null)
        {
            loader = ResourceFactory.class.getClassLoader();
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
     *
     * @param resource the relative name of the resource
     * @return Resource or null
     */
    default Resource newClassPathResource(String resource)
    {
        if (StringUtil.isBlank(resource))
            throw new IllegalArgumentException("Resource String is invalid: " + resource);

        URL url = ResourceFactory.class.getResource(resource);

        if (url == null)
            url = Loader.getResource(resource);
        if (url == null)
            return null;
        try
        {
            URI uri = url.toURI();
            return newResource(uri);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Load a URL into a memory resource.
     * @param url the URL to load into memory
     * @return Resource or null
     * @see #newClassPathResource(String)
     */
    default Resource newMemoryResource(URL url)
    {
        if (url == null)
            throw new IllegalArgumentException("URL is null");

        return new MemoryResource(url);
    }

    /**
     * Construct a resource from a string.
     *
     * @param resource A URL or filename.
     * @return A Resource object.
     */
    default Resource newResource(String resource)
    {
        if (StringUtil.isBlank(resource))
            throw new IllegalArgumentException("Resource String is invalid: " + resource);

        return newResource(URIUtil.toURI(resource));
    }

    /**
     * Construct a Resource from provided path
     *
     * @param path the path
     * @return the Resource for the provided path
     */
    default Resource newResource(Path path)
    {
        if (path == null)
            throw new IllegalArgumentException("Path is null");

        return newResource(URIUtil.correctResourceURI(path.toUri()));
    }

    /**
     * Construct a possible {@link CombinedResource} from a list of URIs
     *
     * @param uris the URIs
     * @return the Resource for the provided path
     */
    default Resource newResource(List<URI> uris)
    {
        if ((uris == null) || (uris.isEmpty()))
            throw new IllegalArgumentException("List of URIs is invalid");

        return combine(uris.stream().map(this::newResource).toList());
    }

    default Resource newResource(URL url)
    {
        if (url == null)
            throw new IllegalArgumentException("URL is null");

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
        if (!uri.getScheme().equalsIgnoreCase("file"))
            throw new IllegalArgumentException("Not an allowed path: " + uri);
        return newResource(URIUtil.toJarFileUri(uri));
    }

    static void registerResourceFactory(String scheme, ResourceFactory resource)
    {
        ResourceFactoryInternals.RESOURCE_FACTORIES.put(scheme, resource);
    }

    static ResourceFactory unregisterResourceFactory(String scheme)
    {
        return ResourceFactoryInternals.RESOURCE_FACTORIES.remove(scheme);
    }

    static ResourceFactory byScheme(String scheme)
    {
        return ResourceFactoryInternals.RESOURCE_FACTORIES.get(scheme);
    }

    static ResourceFactory getBestByScheme(String str)
    {
        return ResourceFactoryInternals.RESOURCE_FACTORIES.getBest(str);
    }

    static ResourceFactory root()
    {
        return ResourceFactoryInternals.ROOT;
    }

    static ResourceFactory.Closeable closeable()
    {
        return new ResourceFactoryInternals.Closeable();
    }

    static ResourceFactory.LifeCycle lifecycle()
    {
        LifeCycle factory = new ResourceFactoryInternals.LifeCycle();
        org.eclipse.jetty.util.component.LifeCycle.start(factory);
        return factory;
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

    static ResourceFactory of(Container container)
    {
        Objects.requireNonNull(container);

        // TODO this needs to be made thread-safe; the getBean/addBean pair must be atomic
        LifeCycle factory = container.getBean(LifeCycle.class);
        if (factory == null)
        {
            factory = lifecycle();
            container.addBean(factory, true);
            final LifeCycle finalFactory = factory;
            container.addEventListener(new org.eclipse.jetty.util.component.LifeCycle.Listener()
            {
                @Override
                public void lifeCycleStopped(org.eclipse.jetty.util.component.LifeCycle event)
                {
                    container.removeBean(this);
                    container.removeBean(finalFactory);
                }
            });
        }
        return factory;
    }

    interface Closeable extends ResourceFactory, java.io.Closeable
    {
        @Override
        void close();
    }

    interface LifeCycle extends org.eclipse.jetty.util.component.LifeCycle, ResourceFactory, Dumpable
    {
    }
}
