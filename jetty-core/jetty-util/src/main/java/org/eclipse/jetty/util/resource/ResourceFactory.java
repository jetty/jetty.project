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

package org.eclipse.jetty.util.resource;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Dumpable;

/**
 * <p>ResourceFactory is the source of new {@link Resource} instances.</p>
 *
 * <p>
 *     Some {@link Resource} objects have an internal allocation / release model,
 *     that the {@link ResourceFactory} is responsible for.
 *     Once a {@link ResourceFactory} is stopped, the {@link Resource}
 *     objects created from that {@link ResourceFactory} are released.
 * </p>
 *
 * <h2>A {@link ResourceFactory.LifeCycle} tied to a Jetty {@link org.eclipse.jetty.util.component.Container}</h2>
 * <pre>
 *     ResourceFactory.LifeCycle resourceFactory = ResourceFactory.of(container);
 *     Resource resource = resourceFactory.newResource(ref);
 * </pre>
 * <p>
 *     The use of {@link ResourceFactory#of(Container)} results in a {@link ResourceFactory.LifeCycle} that is tied
 *     to a specific Jetty {@link org.eclipse.jetty.util.component.Container} such as a {@code Server},
 *     {@code ServletContextHandler}, or {@code WebAppContext}.   This will free the {@code Resource}
 *     instances created by the {@link org.eclipse.jetty.util.resource.ResourceFactory} once
 *     the {@code container} that manages it is stopped.
 * </p>
 *
 * <h2>A {@link ResourceFactory.Closeable} that exists within a {@code try-with-resources} call</h2>
 * <pre>
 *     try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable()) {
 *         Resource resource = resourceFactory.newResource(ref);
 *     }
 * </pre>
 * <p>
 *     The use of {@link ResourceFactory#closeable()} results in a {@link ResourceFactory} that only exists for
 *     the duration of the {@code try-with-resources} code block, once this {@code try-with-resources} is closed,
 *     all {@link Resource} objects associated with that {@link ResourceFactory} are freed as well.
 * </p>
 *
 * <h2>A {@code ResourceFactory} that lives at the JVM level</h2>
 * <pre>
 *     ResourceFactory resourceFactory = ResourceFactory.root();
 *     Resource resource = resourceFactory.newResource(ref);
 * </pre>
 * <p>
 *     The use of {@link ResourceFactory#root()} results in a {@link ResourceFactory} that exists for
 *     the life of the JVM, and the resources allocated via this {@link ResourceFactory} will not
 *     be freed until the JVM exits.
 * </p>
 *
 * <h2>Supported URI Schemes</h2>
 * <p>
 *     By default, the following schemes are supported by Jetty.
 * </p>
 * <dl>
 *     <dt>file</dt>
 *     <dd>The standard Java {@code file:/path/to/dir/} syntax</dd>
 *
 *     <dt>jar</dt>
 *     <dd>The standard Java {@code jar:file:/path/to/file.jar!/} syntax</dd>
 *
 *     <dt>jrt</dt>
 *     <dd>The standard Java {@code jrt:module-name} syntax</dd>
 * </dl>
 * <p>
 *     Special Note: An effort is made to discover any new schemes that
 *     might be present at JVM startup (eg: graalvm and {@code resource:} scheme).
 *     At startup Jetty will access an internal Jetty resource (found in
 *     the jetty-util jar) and seeing what {@code scheme} it is using to access
 *     it, and will register it with a call to
 *     {@link ResourceFactory#registerResourceFactory(String, ResourceFactory)}.
 * </p>
 *
 * <h2>Supporting more Schemes</h2>
 * <p>
 *     You can register a new URI scheme to a {@link ResourceFactory} implementation
 *     using the {@link ResourceFactory#registerResourceFactory(String, ResourceFactory)}
 *     method, which will cause all new uses of ResourceFactory to use this newly
 *     registered scheme.
 * </p>
 * <pre>
 *     URLResourceFactory urlResourceFactory = new URLResourceFactory();
 *     urlResourceFactory.setConnectTimeout(1000);
 *     ResourceFactory.registerResourceFactory("https", urlResourceFactory);
 *
 *     URI web = URI.create("https://eclipse.dev/jetty/");
 *     Resource resource = ResourceFactory.root().newResource(web);
 * </pre>
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
     */
    static Resource combine(Resource... resources)
    {
        return CombinedResource.combine(List.of(resources));
    }

    /**
     * Construct a resource from a uri.
     *
     * @param uri A URI.
     * @return A Resource object, or null if uri points to a location that does not exist.
     */
    Resource newResource(URI uri);

    /**
     * <p>Construct a Resource from a string reference into classloaders.</p>
     *
     * @param resource Resource as string representation
     * @return The new Resource, or null if string points to a location that does not exist
     * @throws IllegalArgumentException if string is blank
     * @see #newClassLoaderResource(String)
     * @deprecated use {@link #newClassLoaderResource(String)} or {@link #newClassLoaderResource(String, boolean)} instead, will be removed in Jetty 12.1.0
     */
    @Deprecated(since = "12.0.1", forRemoval = true)
    default Resource newSystemResource(String resource)
    {
        return newClassLoaderResource(resource);
    }

    /**
     * <p>Construct a Resource from a search of ClassLoaders.</p>
     *
     * <p>
     *     Search order is:
     * </p>
     * <ol>
     *   <li>{@link ClassLoader#getResource(String) java.lang.Thread.currentThread().getContextClassLoader().getResource(String)}</li>
     *   <li>{@link ClassLoader#getResource(String) ResourceFactory.class.getClassLoader().getResource(String)}</li>
     *   <li>{@link ClassLoader#getSystemResource(String) java.lang.ClassLoader.getSystemResource(String)}</li>
     * </ol>
     *
     *
     * @param resource string representation of resource to find in a classloader
     * @param searchSystemClassLoader true to search {@link ClassLoader#getSystemResource(String)}, false to skip
     * @return The new Resource, or null if string points to a location that does not exist
     * @throws IllegalArgumentException if string is blank
     */
    default Resource newClassLoaderResource(String resource, boolean searchSystemClassLoader)
    {
        if (StringUtil.isBlank(resource))
            throw new IllegalArgumentException("Resource String is invalid: " + resource);

        URL url = null;

        List<ClassLoader> classLoaderList = List.of(Thread.currentThread().getContextClassLoader(), ResourceFactory.class.getClassLoader());

        for (ClassLoader classLoader: classLoaderList)
        {
            try
            {
                url = classLoader.getResource(resource);
                if (url == null && resource.startsWith("/"))
                    url = classLoader.getResource(resource.substring(1));
            }
            catch (IllegalArgumentException e)
            {
                // Catches scenario where a bad Windows path like "C:\dev" is
                // improperly escaped, which various downstream classloaders
                // tend to have a problem with
            }
        }

        if (url == null && searchSystemClassLoader)
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
     * <p>Construct a Resource from a search of ClassLoaders.</p>
     *
     * <p>
     * Convenience method {@code .newClassLoaderResource(resource, true)}
     * </p>
     *
     * @param resource string representation of resource to find in a classloader
     * @return The new Resource, or null if string points to a location that does not exist
     * @throws IllegalArgumentException if string is blank
     */
    default Resource newClassLoaderResource(String resource)
    {
        return newClassLoaderResource(resource, true);
    }

    /**
     * <p>Construct a Resource from a search of ClassLoaders.</p>
     *
     * <p>
     * Convenience method {@code .newClassLoaderResource(resource, false)}
     * </p>
     *
     * @param resource the relative name of the resource
     * @return Resource, or null if string points to a location that does not exist
     * @throws IllegalArgumentException if string is blank
     * @deprecated use {@link #newClassLoaderResource(String, boolean)} instead, will be removed in Jetty 12.1.0
     */
    @Deprecated
    default Resource newClassPathResource(String resource)
    {
        return newClassLoaderResource(resource, false);
    }

    /**
     * <p>
     * Load a URL into a memory resource.
     * </p>
     *
     * <p>
     *     A Memory Resource is created from a the contents of
     *     {@link URL#openStream()} and kept in memory from
     *     that point forward.  Never accessing the URL
     *     again to refresh it's contents.
     * </p>
     *
     * @param url the URL to load into memory
     * @return Resource, or null if url points to a location that does not exist
     * @throws IllegalArgumentException if URL is null
     * @see #newClassLoaderResource(String, boolean)
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
     * @return A Resource object, or null if the string points to a location
     *    that does not exist
     * @throws IllegalArgumentException if resource is invalid
     */
    default Resource newResource(String resource)
    {
        if (StringUtil.isBlank(resource))
            throw new IllegalArgumentException("Resource String is invalid: " + resource);

        return newResource(URIUtil.toURI(resource));
    }

    /**
     * Construct a Resource from provided path.
     *
     * @param path the path
     * @return the Resource for the provided path, or null if the path
     *  does not exist
     * @throws IllegalArgumentException if path is null
     */
    default Resource newResource(Path path)
    {
        if (path == null)
            throw new IllegalArgumentException("Path is null");

        return newResource(path.toUri());
    }

    /**
     * Construct a possible combined {@code Resource} from a list of URIs.
     *
     * @param uris the URIs
     * @return the Resource for the provided URIs, or null if all
     *  of the provided URIs do not exist
     * @throws IllegalArgumentException if list of URIs is empty or null
     */
    default Resource newResource(List<URI> uris)
    {
        if ((uris == null) || (uris.isEmpty()))
            throw new IllegalArgumentException("List of URIs is invalid");

        return combine(uris.stream().map(this::newResource).toList());
    }

    /**
     * Construct a {@link Resource} from a provided URL.
     *
     * @param url the URL
     * @return the Resource for the provided URL, or null if the
     *    url points to a location that does not exist
     * @throws IllegalArgumentException if url is null
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
     * Construct a {@link Resource} from a {@code file:} based URI that is mountable (eg: a jar file).
     *
     * @param uri the URI
     * @return the Resource, mounted as a {@link java.nio.file.FileSystem}, or null if
     *   the uri points to a location that does not exist.
     * @throws IllegalArgumentException if provided URI is not "file" scheme.
     */
    default Resource newJarFileResource(URI uri)
    {
        if (!uri.getScheme().equalsIgnoreCase("file"))
            throw new IllegalArgumentException("Not an allowed path: " + uri);
        return newResource(URIUtil.toJarFileUri(uri));
    }

    /**
     * Test to see if provided string is supported.
     *
     * @param str the string to test
     * @return true if it is supported
     */
    static boolean isSupported(String str)
    {
        return ResourceFactoryInternals.isSupported(str);
    }

    /**
     * Test to see if provided uri is supported.
     *
     * @param uri the uri to test
     * @return true if it is supported
     */
    static boolean isSupported(URI uri)
    {
        return ResourceFactoryInternals.isSupported(uri);
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
     * @throws IllegalArgumentException if scheme is blank
     * @see #unregisterResourceFactory(String)
     */
    static void registerResourceFactory(String scheme, ResourceFactory resourceFactory)
    {
        if (StringUtil.isBlank(scheme))
            throw new IllegalArgumentException("Scheme is blank");

        ResourceFactoryInternals.RESOURCE_FACTORIES.put(scheme, resourceFactory);
    }

    /**
     * Unregister a scheme that is supported by the Resource API.
     *
     * @param scheme the scheme to unregister
     * @return the existing {@link ResourceFactory} that was registered to that scheme.
     * @throws IllegalArgumentException if scheme is blank
     * @see #registerResourceFactory(String, ResourceFactory)
     */
    static ResourceFactory unregisterResourceFactory(String scheme)
    {
        if (StringUtil.isBlank(scheme))
            throw new IllegalArgumentException("Scheme is blank");
        return ResourceFactoryInternals.RESOURCE_FACTORIES.remove(scheme);
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
