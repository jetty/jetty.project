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
import java.nio.ByteBuffer;
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

    private static final ReadableByteChannel EOF_READABLE_BYTE_CHANNEL = new ReadableByteChannel()
    {
        @Override
        public int read(ByteBuffer dst)
        {
            return -1;
        }

        @Override
        public boolean isOpen()
        {
            return false;
        }

        @Override
        public void close()
        {
        }
    };

    private static final InputStream EOF_INPUT_STREAM = new InputStream()
    {
        @Override
        public int read()
        {
            return -1;
        }
    };

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
    public InputStream newInputStream() throws IOException
    {
        return EOF_INPUT_STREAM;
    }

    @Override
    public ReadableByteChannel newReadableByteChannel() throws IOException
    {
        return EOF_READABLE_BYTE_CHANNEL;
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
