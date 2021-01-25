//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;

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
    public void close()
    {
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
    public URL getURL()
    {
        return null;
    }

    @Override
    public File getFile() throws IOException
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
    public String[] list()
    {
        return null;
    }

    @Override
    public Resource addPath(String path) throws IOException
    {
        return null;
    }
}
