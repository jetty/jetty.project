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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.Instant;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.URIUtil;

/**
 * {@link ResourceFactory} for {@link java.net.URL} based resources.
 */
public class URLResourceFactory implements ResourceFactory
{
    private int connectTimeout = 1000;
    private boolean useCaches = true;

    public URLResourceFactory()
    {
    }

    public int getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout)
    {
        this.connectTimeout = connectTimeout;
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
            return new URLResource(uri, this.connectTimeout, this.useCaches);
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Bad URI: " + uri, e);
        }
    }

    private static class URLResource extends Resource
    {
        private final URI uri;
        private final URL url;
        private final int connectTimeout;
        private final boolean useCaches;

        public URLResource(URI uri, int connectTimeout, boolean useCaches) throws MalformedURLException
        {
            this.uri = uri;
            this.url = uri.toURL();
            this.connectTimeout = connectTimeout;
            this.useCaches = useCaches;
        }

        private URLConnection newConnection() throws IOException
        {
            URLConnection urlConnection = url.openConnection();
            urlConnection.setUseCaches(this.useCaches);
            urlConnection.setConnectTimeout(this.connectTimeout);
            return urlConnection;
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
            return uri.getSchemeSpecificPart().endsWith("/");
        }

        @Override
        public boolean isReadable()
        {
            return exists();
        }

        @Override
        public URI getURI()
        {
            return URIUtil.correctFileURI(uri);
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
            URI newURI = resolve(uri, subUriPath);
            try
            {
                return new URLResource(newURI, this.connectTimeout, this.useCaches);
            }
            catch (MalformedURLException e)
            {
                return null;
            }
        }

        // This could probably live in URIUtil, but it's awefully specific to URLResourceFactory.
        private static URI resolve(URI parent, String path)
        {
            if (parent.isOpaque() && parent.getPath() == null)
            {
                URI resolved = resolve(URI.create(parent.getRawSchemeSpecificPart()), path);
                return URI.create(parent.getScheme() + ":" + resolved.toASCIIString());
            }
            else if (parent.getPath() != null)
            {
                return parent.resolve(path);
            }
            else
            {
                // Not possible to use URLs that without a path in Jetty.
                throw new RuntimeException("URL without path not supported by Jetty: " + parent);
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
