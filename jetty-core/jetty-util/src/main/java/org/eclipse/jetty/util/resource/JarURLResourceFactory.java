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
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.URIUtil;

/**
 * {@link ResourceFactory} for jar: {@link URL} based resources.
 */
public class JarURLResourceFactory implements ResourceFactory
{
    private boolean useCaches = true;

    public JarURLResourceFactory()
    {
    }

    public boolean isUseCaches()
    {
        return useCaches;
    }

    public void setUseCaches(boolean useCaches)
    {
        this.useCaches = useCaches;
    }

    @Override
    public Resource newResource(final URI uri)
    {
        try
        {
            return new JarURLResource(uri, this.useCaches);
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Bad URI: " + uri, e);
        }
    }

    private static class JarURLResource extends Resource
    {
        private final URI uri;
        private final URL url;
        private final boolean useCaches;

        private JarURLResource(URI uri, boolean useCaches) throws MalformedURLException
        {
            if (!"jar".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("URI does not have 'jar:' scheme : " + uri);
            this.uri = uri;
            this.url = uri.toURL();
            this.useCaches = useCaches;
        }

        private JarURLConnection newConnection() throws IOException
        {
            JarURLConnection jarUrlConnection = (JarURLConnection)url.openConnection();
            jarUrlConnection.setUseCaches(this.useCaches);
            return jarUrlConnection;
        }

        @Override
        public Path getPath()
        {
            return null;
        }

        @Override
        public boolean isContainedIn(Resource r)
        {
            // compare starting URIs?
            return false;
        }

        @Override
        public boolean isDirectory()
        {
            try
            {
                JarURLConnection connection = newConnection();
                JarEntry jarEntry = connection.getJarEntry();
                return jarEntry != null && jarEntry.isDirectory();
            }
            catch (IOException e)
            {
                return false;
            }
        }

        @Override
        public List<Resource> list()
        {
            try
            {
                JarURLConnection connection = newConnection();
                JarEntry jarEntry = connection.getJarEntry();
                if (jarEntry == null || !jarEntry.isDirectory())
                    return List.of();

                int idx = uri.getRawSchemeSpecificPart().lastIndexOf("!/");
                String currentSubDir = uri.getRawSchemeSpecificPart().substring(idx + 2);
                if (!currentSubDir.endsWith("/"))
                    currentSubDir += "/";
                String finalCwd = currentSubDir;


                JarFile jarFile = connection.getJarFile();
                return jarFile.stream()
                    .filter(je ->
                    {
                        if (!je.getName().startsWith(finalCwd))
                            return false;
                        if (je.getName().equals(finalCwd))
                            return false;

                        String name = je.getName();
                        int lastIndex = name.lastIndexOf('/');
                        int index = name.indexOf('/', finalCwd.length());

                        if (index == -1)
                            return true;
                        if (index != lastIndex)
                            return false;
                        return lastIndex == name.length() - 1;
                    })
                    .map(je -> resolve(je.getName().substring(finalCwd.length())))
                    .toList();
            }
            catch (IOException e)
            {
                return List.of();
            }
        }

        @Override
        public boolean isReadable()
        {
            return exists();
        }

        @Override
        public URI getURI()
        {
            return uri;
        }

        @Override
        public String getName()
        {
            return uri.toASCIIString();
        }

        @Override
        public String getFileName()
        {
            return FileID.getFileName(uri);
        }

        @Override
        public Resource resolve(String subUriPath)
        {
            URI newURI = URIUtil.addPath(uri, subUriPath);
            try
            {
                return new JarURLResource(newURI, this.useCaches);
            }
            catch (MalformedURLException e)
            {
                return null;
            }
        }

        @Override
        public boolean exists()
        {
            try
            {
                newConnection();
                return true;
            }
            catch (IOException e)
            {
                return false;
            }
        }

        @Override
        public InputStream newInputStream() throws IOException
        {
            URLConnection urlConnection = newConnection();
            return urlConnection.getInputStream();
        }

        @Override
        public Instant lastModified()
        {
            try
            {
                URLConnection urlConnection = newConnection();
                return Instant.ofEpochMilli(urlConnection.getLastModified());
            }
            catch (IOException e)
            {
                return Instant.EPOCH;
            }
        }

        @Override
        public long length()
        {
            try
            {
                URLConnection urlConnection = newConnection();
                return urlConnection.getContentLengthLong();
            }
            catch (IOException e)
            {
                return -1;
            }
        }

        @Override
        public ReadableByteChannel newReadableByteChannel() throws IOException
        {
            return Channels.newChannel(newInputStream());
        }

        @Override
        public boolean isAlias()
        {
            return false;
        }

        @Override
        public URI getRealURI()
        {
            return getURI();
        }

        @Override
        public String toString()
        {
            return String.format("URLResource@%X(%s)", this.uri.hashCode(), this.uri.toASCIIString());
        }
    }
}
