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

package org.eclipse.jetty.ee10.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * MavenResource
 *
 * A helper class to allow Resources to be used in maven pom.xml configuration by
 * providing a no-arg constructor and a setter that accepts a simple string as a
 * file location. This class delegates to a real Resource obtained using a
 * ResourceFactory.
 */
public class MavenResource extends Resource
{
    private static final ResourceFactory __resourceFactory = ResourceFactory.root();
    private String _resourceString;
    private Resource _resource;

    public MavenResource()
    {
    }

    @Override
    public void copyTo(Path destination) throws IOException
    {
        if (_resource == null)
            return;
        _resource.copyTo(destination);
    }

    @Override
    public boolean exists()
    {
        if (_resource == null)
            return false;
        return _resource.exists();
    }

    @Override
    public Collection<Resource> getAllResources()
    {
        if (_resource == null)
            return null;
        return _resource.getAllResources();
    }

    @Override
    public String getFileName()
    {
        if (_resource == null)
            return null;
        return _resource.getFileName();
    }

    @Override
    public String getName()
    {
        if (_resource == null)
            return null;
        return _resource.getName();
    }

    @Override
    public Path getPath()
    {
        if (_resource == null)
            return null;

        return _resource.getPath();
    }

    @Override
    public URI getRealURI()
    {
        if (_resource == null)
            return null;
        return _resource.getRealURI();
    }

    public String getResourceAsString()
    {
        return _resourceString;
    }

    public void setResourceAsString(String resourceString)
    {
        _resourceString = resourceString;
        _resource = __resourceFactory.newResource(_resourceString);
    }

    @Override
    public URI getURI()
    {
        if (_resource == null)
            return null;

        return _resource.getURI();
    }

    @Override
    public boolean isAlias()
    {
        if (_resource == null)
            return false;
        return _resource.isAlias();
    }

    @Override
    public boolean isContainedIn(Resource container)
    {
        if (_resource == null)
            return false;
        return _resource.isContainedIn(container);
    }

    @Override
    public boolean contains(Resource other)
    {
        if (_resource == null)
            return false;
        return _resource.contains(other);
    }

    @Override
    public Path getPathTo(Resource other)
    {
        if (_resource == null)
            return null;
        return _resource.getPathTo(other);
    }

    @Override
    public boolean isDirectory()
    {
        if (_resource == null)
            return false;

        return _resource.isDirectory();
    }

    @Override
    public boolean isReadable()
    {
        if (_resource == null)
            return false;

        return _resource.isReadable();
    }

    @Override
    public Iterator<Resource> iterator()
    {
        if (_resource == null)
            return null;
        return _resource.iterator();
    }

    @Override
    public Instant lastModified()
    {
        if (_resource == null)
            return null;
        return _resource.lastModified();
    }

    @Override
    public long length()
    {
        if (_resource == null)
            return -1;
        return _resource.length();
    }

    @Override
    public List<Resource> list()
    {
        if (_resource == null)
            return null;
        return _resource.list();
    }

    @Override
    public InputStream newInputStream() throws IOException
    {
        if (_resource == null)
            return null;
        return _resource.newInputStream();
    }

    @Override
    public ReadableByteChannel newReadableByteChannel() throws IOException
    {
        if (_resource == null)
            return null;
        return _resource.newReadableByteChannel();
    }

    @Override
    public Resource resolve(String subUriPath)
    {
        if (_resource == null)
            return null;
        return _resource.resolve(subUriPath);
    }
}