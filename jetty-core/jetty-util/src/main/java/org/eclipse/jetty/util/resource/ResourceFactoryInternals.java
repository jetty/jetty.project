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
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ResourceFactoryInternals
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceFactoryInternals.class);
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
        protected void onMounted(FileSystemPool.Mount mount, URI uri)
        {
            // Since this ROOT ResourceFactory and has no lifecycle that can clean up
            // the mount, we shall report this mount as a leak
            if (LOG.isDebugEnabled())
                LOG.warn("Leaked {} for {}", mount, uri, new Throwable());
            else
                LOG.warn("Leaked {} for {}", mount, uri);
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

    interface Mountable
    {
        List<FileSystemPool.Mount> getMounts();
    }

    static class Closeable implements ResourceFactory.Closeable, Mountable
    {
        private final CompositeResourceFactory _compositeResourceFactory = new CompositeResourceFactory();

        @Override
        public Resource newResource(URI uri)
        {
            return _compositeResourceFactory.newResource(uri);
        }

        @Override
        public void close()
        {
            for (FileSystemPool.Mount mount : _compositeResourceFactory.getMounts())
                IO.close(mount);
            _compositeResourceFactory.clearMounts();
        }

        public List<FileSystemPool.Mount> getMounts()
        {
            return _compositeResourceFactory.getMounts();
        }
    }

    static class LifeCycle extends AbstractLifeCycle implements ResourceFactory.LifeCycle
    {
        private final CompositeResourceFactory _compositeResourceFactory = new CompositeResourceFactory();

        @Override
        public Resource newResource(URI uri)
        {
            return _compositeResourceFactory.newResource(uri);
        }

        @Override
        protected void doStop() throws Exception
        {
            for (FileSystemPool.Mount mount : _compositeResourceFactory.getMounts())
                IO.close(mount);
            _compositeResourceFactory.clearMounts();
            super.doStop();
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            List<URI> referencedUris = _compositeResourceFactory.getMounts().stream()
                .map(mount -> mount.root().getURI())
                .toList();
            Dumpable.dumpObjects(out, indent, this, new DumpableCollection("newResourceReferences", referencedUris));
        }
    }

    static class CompositeResourceFactory implements ResourceFactory
    {
        private final List<FileSystemPool.Mount> _mounts = new CopyOnWriteArrayList<>();

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

                    // Correct any `file:/path` to `file:///path` mistakes
                    uri = URIUtil.correctFileURI(uri);
                }

                ResourceFactory resourceFactory = RESOURCE_FACTORIES.get(uri.getScheme());
                if (resourceFactory == null)
                    throw new IllegalArgumentException("URI scheme not supported: " + uri);
                if (resourceFactory instanceof MountedPathResourceFactory)
                {
                    FileSystemPool.Mount mount = mountIfNeeded(uri);
                    if (mount != null)
                    {
                        _mounts.add(mount);
                        onMounted(mount, uri);
                    }
                }
                return resourceFactory.newResource(uri);
            }
            catch (URISyntaxException | ProviderNotFoundException ex)
            {
                throw new IllegalArgumentException("Unable to create resource from: " + uri, ex);
            }
        }

        /**
         * <p>Mount a URI if it is needed.</p>
         *
         * @param uri The URI to mount that may require a FileSystem (e.g. "jar:file://tmp/some.jar!/directory/file.txt")
         * @return A reference counted {@link FileSystemPool.Mount} for that file system or null. Callers should call
         * {@link FileSystemPool.Mount#close()} once they no longer require this specific Mount.
         * @throws IllegalArgumentException If the uri could not be mounted.
         */
        private FileSystemPool.Mount mountIfNeeded(URI uri)
        {
            // do not mount if it is not a jar URI
            String scheme = uri.getScheme();
            if (!"jar".equalsIgnoreCase(scheme))
                return null;

            // Do not mount if we have already mounted
            // TODO there is probably a better way of doing this other than string comparisons
            String uriString = uri.toASCIIString();
            for (FileSystemPool.Mount mount : _mounts)
            {
                if (uriString.startsWith(mount.root().toString()))
                    return null;
            }

            try
            {
                return FileSystemPool.INSTANCE.mount(uri);
            }
            catch (IOException ioe)
            {
                throw new IllegalArgumentException("Unable to mount: " + uri, ioe);
            }
        }

        protected void onMounted(FileSystemPool.Mount mount, URI uri)
        {
            // override to specify behavior
        }

        public List<FileSystemPool.Mount> getMounts()
        {
            return _mounts;
        }

        public void clearMounts()
        {
            _mounts.clear();
        }
    }
}
