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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.IO;
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

    Resource newResource(URI uri);

    default Resource newResource(String resource) throws IOException
    {
        return newResource(Resource.toURI(resource));
    }

    default Resource newResource(Path path)
    {
        return newResource(path.toUri());
    }

    default ResourceCollection newResource(List<URI> uris)
    {
        return new ResourceCollection(uris.stream().map(this::newResource).collect(Collectors.toList()));
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
        if (!Resource.isArchive(uri))
            throw new IllegalArgumentException("Path is not a Java Archive: " + uri);
        if (!uri.getScheme().equalsIgnoreCase("file"))
            throw new IllegalArgumentException("Not an allowed path: " + uri);
        return newResource(Resource.toJarFileUri(uri));
    }

    static ResourceFactory of(Resource baseResource)
    {
        if (baseResource == null)
            return ROOT;

        if (baseResource instanceof ResourceFactory resourceFactory)
            return resourceFactory;

        return new ResourceFactory()
        {
            @Override
            public Resource newResource(URI resource)
            {
                // TODO add an optimized pathway that keeps the URI and doesn't go via String
                try
                {
                    return newResource(resource.toString());
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Resource newResource(String resource) throws IOException
            {
                return baseResource.resolve(resource);
            }
        };
    }

    static ResourceFactory.Closeable closeable()
    {
        return new Closeable();
    }

    static ResourceFactory of(Container container)
    {
        if (container == null)
            return ROOT;

        ContainerResourceFactory factory = container.getBean(ContainerResourceFactory.class);
        if (factory == null)
        {
            factory = new ContainerResourceFactory();
            LifeCycle.start(factory);
            container.addBean(factory, true);
        }
        return factory;
    }

    ResourceFactory ROOT = new ResourceFactory()
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
        public Resource newResource(String resource) throws IOException
        {
            return newResource(Resource.toURI(resource));
        }
    };

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
