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
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.List;

/**
 * EmptyResource
 *
 * Represents a resource that does does not refer to any file, url, jar etc.
 */
public class EmptyResource extends Resource
{
    public static final Resource INSTANCE = new EmptyResource();

    private EmptyResource()
    {
    }

    @Override
    public boolean isContainedIn(Resource r) throws MalformedURLException
    {
        return false;
    }

    @Override
    public boolean exists()
    {
        return false;
    }

    @Override
    public boolean isDirectory()
    {
        return false;
    }

    @Override
    public long lastModified()
    {
        return 0;
    }

    @Override
    public long length()
    {
        return 0;
    }

    @Override
    public URI getURI()
    {
        return null;
    }

    @Override
    public Path getPath()
    {
        return null;
    }

    @Override
    public String getName()
    {
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return null;
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return null;
    }

    @Override
    public boolean delete() throws SecurityException
    {
        return false;
    }

    @Override
    public boolean renameTo(Resource dest) throws SecurityException
    {
        return false;
    }

    @Override
    public List<String> list()
    {
        return null;
    }

    @Override
    public Resource resolve(String subUriPath) throws IOException
    {
        return this;
    }
}
