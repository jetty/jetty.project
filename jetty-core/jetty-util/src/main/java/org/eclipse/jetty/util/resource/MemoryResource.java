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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.jetty.util.IO;

/**
 * <p>An in memory Resource created from a {@link URL}</p>
 */
public class MemoryResource extends Resource
{

    private final URI _uri;
    private final long _created = System.currentTimeMillis();
    private final byte[] _bytes;

    public MemoryResource(URL url)
    {
        try
        {
            _uri = Objects.requireNonNull(url).toURI();
            _bytes = IO.readBytes(url.openStream());
        }
        catch (IOException | URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Path getPath()
    {
        return null;
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        return false;
    }

    @Override
    public URI getURI()
    {
        return _uri;
    }

    @Override
    public String getName()
    {
        return _uri.getPath();
    }

    @Override
    public long lastModified()
    {
        return _created;
    }

    @Override
    public long length()
    {
        return _bytes.length;
    }

    @Override
    public InputStream newInputStream() throws IOException
    {
        return new ByteArrayInputStream(_bytes);
    }

    @Override
    public ReadableByteChannel newReadableByteChannel() throws IOException
    {
        return Channels.newChannel(newInputStream());
    }

    @Override
    public Resource resolve(String subUriPath)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean exists()
    {
        return true;
    }

    @Override
    public String toString()
    {
        return getName();
    }
}
