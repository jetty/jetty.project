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
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Index;
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
        RESOURCE_FACTORIES.put("jar", new MountedPathResourceFactory());
        PathResourceFactory pathResourceFactory = new PathResourceFactory();
        RESOURCE_FACTORIES.put("file", pathResourceFactory);
        RESOURCE_FACTORIES.put("jrt", pathResourceFactory);

        if (NativeImagePathResourceFactory.ENABLE_GRAALVM_RESOURCE_SCHEME)
            RESOURCE_FACTORIES.put("resource", new NativeImagePathResourceFactory());
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

    static class Closeable implements ResourceFactory.Closeable
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
            Dumpable.dumpObjects(out, indent, this, new DumpableCollection("mounts", _compositeResourceFactory.getMounts()));
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

                ResourceFactory resourceFactory = ResourceFactory.byScheme(uri.getScheme());
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
         * {@link FileSystemPool.Mount#close()} once they no longer require any resources from a mounted resource.
         * @throws IllegalArgumentException If the uri could not be mounted.
         */
        private FileSystemPool.Mount mountIfNeeded(URI uri)
        {
            String scheme = uri.getScheme();
            if (!"jar".equalsIgnoreCase(scheme))
                return null;
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
