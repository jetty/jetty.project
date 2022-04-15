//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.paths;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Basic ClassLoader based on an existing {@link PathCollection}
 * FIXME - WORK IN PROGRESS
 */
public class PathClassLoader extends ClassLoader
{
    private final PathCollection pathCollection;

    public PathClassLoader(PathCollection pathCollection)
    {
        this.pathCollection = pathCollection;
    }

    public PathCollection getPathCollection()
    {
        return pathCollection;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        String className = name.replace('.', '/').concat(".class");
        Path result = pathCollection.resolveFirstExisting(className);
        if (result == null)
            throw new ClassNotFoundException(name);

        try (SeekableByteChannel byteChannel = Files.newByteChannel(result))
        {
            long classSize = Files.size(result);
            if (classSize > Integer.MAX_VALUE)
                throw new ClassNotFoundException("Class file is too large: " + name);
            int intClassSize = (int)classSize;
            // this could result in an OOM in some extreme cases
            // but this is not something we can solve, only fail
            ByteBuffer buffer = ByteBuffer.allocate(intClassSize);
            byteChannel.read(buffer);
            buffer.flip();
            return defineClass(name, buffer, null);
        }
        catch (IOException e)
        {
            throw new ClassNotFoundException(name, e);
        }
    }

    @Override
    protected URL findResource(String name)
    {
        Path result = pathCollection.resolveFirstExisting(name);
        if (result == null)
            return null;
        try
        {
            return result.toUri().toURL();
        }
        catch (MalformedURLException e)
        {
            // per ClassLoader.findResource(String) this method should return null from failed attempt to load resource
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException
    {
        List<URL> results = new ArrayList<>();
        for (Path path : pathCollection.resolveAll(name, Files::exists))
        {
            results.add(path.toUri().toURL());
        }
        return Collections.enumeration(results);
    }
}
