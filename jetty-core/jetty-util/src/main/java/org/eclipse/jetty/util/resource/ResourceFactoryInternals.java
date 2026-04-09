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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;

class ResourceFactoryInternals
{
    private static final Path CURRENT_WORKING_DIR;

    /**
     * The Index (Map) of URI schemes to ResourceFactory implementations that is used by {@link CompositeResourceFactory}
     */
    static final Index.Mutable<ResourceFactory> RESOURCE_FACTORIES = new Index.Builder<ResourceFactory>()
        .caseSensitive(false)
        .mutable()
        .build();

    static
    {
        CURRENT_WORKING_DIR = Path.of(System.getProperty("user.dir"));

        // The default resource factories
        MountedPathResourceFactory mountedPathResourceFactory = new MountedPathResourceFactory();
        RESOURCE_FACTORIES.put("jar", mountedPathResourceFactory);
        PathResourceFactory pathResourceFactory = new PathResourceFactory();
        RESOURCE_FACTORIES.put("file", pathResourceFactory);
        RESOURCE_FACTORIES.put("jrt", pathResourceFactory);

        /* Best-effort attempt to support an alternate FileSystem type that is in use for classpath
         * resources.
         * 
         * The build.properties is present in the jetty-util jar, and explicitly included for reflection
         * with native-image (unlike classes, which are not accessible by default), so we use that
         * resource as a reference.
         */
        URL url = ResourceFactoryInternals.class.getResource("/org/eclipse/jetty/version/build.properties");
        if ((url != null) && !RESOURCE_FACTORIES.contains(url.getProtocol()))
        {
            ResourceFactory resourceFactory;
            if (GraalIssue5720PathResource.isAffectedURL(url))
            {
                resourceFactory = new GraalIssue5720PathResourceFactory();
            }
            else
            {
                resourceFactory = url.toString().contains("!/") ? mountedPathResourceFactory : pathResourceFactory;
            }

            RESOURCE_FACTORIES.put(url.getProtocol(), resourceFactory);
        }
    }

    static ResourceFactory ROOT = new CompositeResourceFactory()
    {
        @Override
        public void onNewFileSystem(FileSystem fs, Path path, URI uri)
        {
            // Since this ROOT ResourceFactory and has no lifecycle that can clean up
            // Close the FileSystem, we shall report this mount as a leak
            if (LOG.isDebugEnabled())
                LOG.warn("Leaked {} for {}", fs, uri, new Throwable());
            else
                LOG.warn("Leaked {} for {}", fs, uri);
        }
    };

    /**
     * Test uri to know if a {@link ResourceFactory} is registered for it.
     *
     * @param uri the uri to test
     * @return true if a ResourceFactory is registered to support the uri
     * @see ResourceFactory#registerResourceFactory(String, ResourceFactory)
     * @see ResourceFactory#unregisterResourceFactory(String)
     * @see #isSupported(String)
     */
    static boolean isSupported(URI uri) // TODO: boolean isSupported
    {
        if (uri == null || uri.getScheme() == null)
            return false;
        return RESOURCE_FACTORIES.get(uri.getScheme()) != null;
    }

    /**
     * Test string to know if a {@link ResourceFactory} is registered for it.
     *
     * @param str the string representing the resource location
     * @return true if a ResourceFactory is registered to support the string representation
     * @see org.eclipse.jetty.util.Index#getBest(String)
     * @see ResourceFactory#registerResourceFactory(String, ResourceFactory)
     * @see ResourceFactory#unregisterResourceFactory(String)
     * @see #isSupported(URI)
     */
    static boolean isSupported(String str)
    {
        if (StringUtil.isBlank(str))
            return false;
        return RESOURCE_FACTORIES.getBest(str) != null;
    }

    interface Tracking
    {
        int getTrackingCount();
    }

    static class Closeable implements ResourceFactory.Closeable, Tracking
    {
        private boolean closed = false;
        private final CompositeResourceFactory _compositeResourceFactory = new CompositeResourceFactory();

        @Override
        public Resource newResource(URI uri)
        {
            if (closed)
                throw new IllegalStateException("Unable to create new Resource on closed ResourceFactory");
            return _compositeResourceFactory.newResource(uri);
        }

        @Override
        public void close()
        {
            closed = true;
            _compositeResourceFactory.closeFileSystems();
        }

        public int getTrackingCount()
        {
            return _compositeResourceFactory.mounted.size();
        }
    }

    static class LifeCycle extends AbstractLifeCycle implements ResourceFactory.LifeCycle
    {
        private final CompositeResourceFactory _compositeResourceFactory = new CompositeResourceFactory();

        @Override
        public Resource newResource(URI uri)
        {
            // TODO: add check that LifeCycle is started before allowing this method to be used?
            return _compositeResourceFactory.newResource(uri);
        }

        @Override
        protected void doStop() throws Exception
        {
            _compositeResourceFactory.closeFileSystems();
            super.doStop();
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            List<URI> referencedUris = _compositeResourceFactory.mounted.stream()
                .map(PathResource::getURI)
                .toList();
            Dumpable.dumpObjects(out, indent, this, new DumpableCollection("newResourceReferences", referencedUris));
        }
    }

    static class CompositeResourceFactory implements ResourceFactory
    {
        private final List<MountedPathResource> mounted = new CopyOnWriteArrayList<>();

        @Override
        public Resource newResource(URI uri)
        {
            if (uri == null)
                return null;

            try
            {
                // If the URI is not absolute
                if (!uri.isAbsolute())
                {
                    // If it is an absolute path,
                    if (uri.toString().startsWith("/"))
                        // just add the scheme
                        uri = new URI("file", uri.toString(), null);
                    else
                        // otherwise resolve against the current directory
                        uri = CURRENT_WORKING_DIR.toUri().resolve(uri);

                    // Correct any mistakes like `file:/path` (to `file:///path`)
                    uri = URIUtil.correctURI(uri);
                }

                ResourceFactory resourceFactory = RESOURCE_FACTORIES.get(uri.getScheme());
                if (resourceFactory == null)
                    throw new IllegalArgumentException("URI scheme not registered: " + uri.getScheme());
                Resource resource = resourceFactory.newResource(uri);
                if (resource instanceof MountedPathResource mountedPathResource)
                {
                    if (mountedPathResource.getFileSystem() != null)
                    {
                        mounted.add(mountedPathResource);
                        onNewFileSystem(mountedPathResource.getFileSystem(), mountedPathResource.getPath(), uri);
                    }
                }
                return resource;
            }
            catch (URISyntaxException | ProviderNotFoundException ex)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to create resource from: {}", uri, ex);
                throw new IllegalArgumentException("Unable to create resource", ex);
            }
        }

        /**
         * @deprecated FileSystemPool is no longer used.
         */
        @Deprecated(since = "12.1.9", forRemoval = true)
        protected void onMounted(FileSystemPool.Mount mount, URI uri)
        {
            // does nothing
        }

        /**
         * @deprecated FileSystemPool is no longer used.
         */
        @Deprecated(since = "12.1.9", forRemoval = true)
        public List<FileSystemPool.Mount> getMounts()
        {
            return List.of();
        }

        /**
         * @deprecated FileSystemPool is no longer used.
         */
        @Deprecated(since = "12.1.9", forRemoval = true)
        public void clearMounts()
        {
            // does nothing
        }

        public void onNewFileSystem(FileSystem fs, Path path, URI uri)
        {

        }

        protected void closeFileSystems()
        {
            for (MountedPathResource mounted : mounted)
            {
                // Skip MountedPathResource that doesn't have a mounted newly FileSystem
                if (mounted.getFileSystem() == null)
                    continue;
                try
                {
                    mounted.getFileSystem().close();
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failed to close FileSystem: {}", mounted.getFileSystem(), e);
                }
            }
            mounted.clear();
        }
    }
}
