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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ResourceFactoryInternals
{
    private static final Logger LOG = LoggerFactory.getLogger(ResourceFactoryInternals.class);

    static ResourceFactory ROOT = new ResourceFactory()
    {
        @Override
        public Resource newResource(URI uri)
        {
            FileSystemPool.Mount mount = mountIfNeeded(uri);
            if (mount != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.warn("Leaked {} for {}", mount, uri, new Throwable());
                else
                    LOG.warn("Leaked {} for {}", mount, uri);
            }
            return Resource.create(uri);
        }

        @Override
        public Resource newResource(String resource)
        {
            return newResource(URIUtil.toURI(resource));
        }
    };

    /**
     * <p>Mount a URI if it is needed.</p>
     *
     * @param uri The URI to mount that may require a FileSystem (e.g. "jar:file://tmp/some.jar!/directory/file.txt")
     * @return A reference counted {@link FileSystemPool.Mount} for that file system or null. Callers should call
     * {@link FileSystemPool.Mount#close()} once they no longer require any resources from a mounted resource.
     * @throws IllegalArgumentException If the uri could not be mounted.
     */
    static FileSystemPool.Mount mountIfNeeded(URI uri)
    {
        if (uri == null)
            return null;
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("jar"))
            return null;
        try
        {
            return FileSystemPool.INSTANCE.mount(uri);
        }
        catch (IOException ioe)
        {
            throw new IllegalArgumentException(ioe);
        }
    }

    static class Closeable implements ResourceFactory.Closeable
    {
        private final List<FileSystemPool.Mount> _mounts = new CopyOnWriteArrayList<>();

        @Override
        public Resource newResource(URI uri)
        {
            FileSystemPool.Mount mount = mountIfNeeded(uri);
            if (mount != null)
            {
                // we got a mount, remember it.
                _mounts.add(mount);
            }
            return Resource.create(uri);
        }

        @Override
        public void close()
        {
            for (FileSystemPool.Mount mount : _mounts)
                IO.close(mount);
            _mounts.clear();
        }
    }

    static class LifeCycle extends AbstractLifeCycle implements ResourceFactory.LifeCycle
    {
        private final List<FileSystemPool.Mount> _mounts = new CopyOnWriteArrayList<>();

        @Override
        public Resource newResource(URI uri)
        {
            FileSystemPool.Mount mount = mountIfNeeded(uri);
            if (mount != null)
                _mounts.add(mount);
            return Resource.create(uri);
        }

        @Override
        protected void doStop() throws Exception
        {
            for (FileSystemPool.Mount mount : _mounts)
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
