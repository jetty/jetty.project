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

        return newResource(path.toUri());
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

    /**
     * Construct a {@link Resource} from a provided URL
     *
     * @param url the URL
     * @return the Resource for the provided URL
     */
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

    /**
     * Construct a {@link Resource} from a {@code file:} based URI that is mountable (eg: a jar file)
     *
     * @param uri the URI
     * @return the Resource, mounted as a {@link java.nio.file.FileSystem}
     */
    default Resource newJarFileResource(URI uri)
    {
        if (!uri.getScheme().equalsIgnoreCase("file"))
            throw new IllegalArgumentException("Not an allowed path: " + uri);
        return newResource(URIUtil.toJarFileUri(uri));
    }

    /**
     * Register a new ResourceFactory that can handle the specific scheme for the Resource API.
     *
     * <p>
     *     This allows
     * </p>
     *
     * @param scheme the scheme to support (eg: `ftp`, `http`, `resource`, etc)
     * @param resourceFactory the ResourceFactory to be responsible for the registered scheme.
     * @see #unregisterResourceFactory(String)
     * @see #byScheme(String)
     * @see #getBestByScheme(String)
     */
    static void registerResourceFactory(String scheme, ResourceFactory resourceFactory)
    {
        ResourceFactoryInternals.RESOURCE_FACTORIES.put(scheme, resourceFactory);
    }

    /**
     * Unregister a scheme that is supported by the Resource API.
     *
     * @param scheme the scheme to unregister
     * @return the existing {@link ResourceFactory} that was registered to that scheme.
     * @see #registerResourceFactory(String, ResourceFactory)
     * @see #byScheme(String)
     * @see #getBestByScheme(String)
     */
    static ResourceFactory unregisterResourceFactory(String scheme)
    {
        return ResourceFactoryInternals.RESOURCE_FACTORIES.remove(scheme);
    }

    /**
     * Get the {@link ResourceFactory} that is registered for the specific scheme.
     *
     * <pre>{@code
     * .byScheme("jar") == ResourceFactory supporting jar
     * .byScheme("jar:file://foo.jar!/") == null // full url strings not supported)
     * }</pre>
     *
     * @param scheme the scheme to look up
     * @return the {@link ResourceFactory} responsible for the scheme, null if no {@link ResourceFactory} handles the scheme.
     * @see #registerResourceFactory(String, ResourceFactory)
     * @see #unregisterResourceFactory(String)
     * @see #getBestByScheme(String)
     */
    static ResourceFactory byScheme(String scheme)
    {
        return ResourceFactoryInternals.RESOURCE_FACTORIES.get(scheme);
    }

    /**
     * Get the best ResourceFactory for the provided scheme.
     *
     * <p>
     * Unlike {@link #byScheme(String)}, this supports arbitrary Strings, that might start with a supported scheme.
     * </p>
     *
     * @param scheme the scheme to look up
     * @return the ResourceFactory that best fits the provided scheme.
     * @see org.eclipse.jetty.util.Index#getBest(String)
     * @see #registerResourceFactory(String, ResourceFactory)
     * @see #unregisterResourceFactory(String)
     * @see #byScheme(String)
     */
    static ResourceFactory getBestByScheme(String scheme)
    {
        return ResourceFactoryInternals.RESOURCE_FACTORIES.getBest(scheme);
    }

    /**
     * The JVM wide (root) ResourceFactory.
     *
     * <p>
     * Resources allocated this way are not released until the JVM is closed.
     * </p>
     *
     * <p>
     * If you have a ResourceFactory need that needs to clean up it's resources at runtime, use {@link #closeable()} or {@link #lifecycle()} instead.
     * </p>
     *
     * @return the JVM wide ResourceFactory.
     * @see #closeable()
     * @see #lifecycle()
     */
    static ResourceFactory root()
    {
        return ResourceFactoryInternals.ROOT;
    }

    /**
     * A ResourceFactory that can close it's opened resources using the Java standard {@link AutoCloseable} techniques.
     *
     * @return a ResourceFactory that can be closed in a try-with-resources code block
     */
    static ResourceFactory.Closeable closeable()
    {
        return new ResourceFactoryInternals.Closeable();
    }

    /**
     * A ResourceFactory that implements the Jetty LifeCycle.
     *
     * <p>
     * This style of ResourceFactory can be attached to the normal Jetty LifeCycle to clean up it's allocated resources.
     * </p>
     *
     * @return the ResourceFactory that implements {@link org.eclipse.jetty.util.component.LifeCycle}
     */
    static ResourceFactory.LifeCycle lifecycle()
    {
        LifeCycle factory = new ResourceFactoryInternals.LifeCycle();
        org.eclipse.jetty.util.component.LifeCycle.start(factory);
        return factory;
    }

    /**
     * A new ResourceFactory from a provided Resource, to base {@link #newResource(URI)} and {@link #newResource(String)} calls against.
     *
     * @param baseResource the resource to base this ResourceFactory from
     * @return the ResourceFactory that builds from the Resource
     */
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

    /**
     * A new ResourceFactory tied to a Jetty Component {@link Container}, to allow
     * its allocated resources to be cleaned up during the normal component lifecycle behavior.
     *
     * <p>
     *     This is safe to call repeatedly against the same {@link Container}, the first
     *     call will create a ResourceFactory from {@link #lifecycle()} and add it as managed
     *     to the {@link Container}, subsequent calls will return the same ResourceFactory
     *     from the provided {@link Container}.
     * </p>
     *
     * @param container the container this ResourceFactory belongs to
     * @return the ResourceFactory that belongs to this container
     */
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
