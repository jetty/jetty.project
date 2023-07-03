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
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static class URLResource extends Resource implements AutoCloseable
    {
        private static final Logger LOG = LoggerFactory.getLogger(URLResource.class);
        protected final AutoLock lock = new AutoLock();
        protected URLConnection connection;
        protected InputStream in = null;

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

        @Override
        public void close()
        {
            try (AutoLock l = lock.lock())
            {
                if (in != null)
                {
                    try
                    {
                        in.close();
                    }
                    catch (IOException e)
                    {
                        LOG.trace("IGNORED", e);
                    }
                    in = null;
                }

                if (connection != null)
                    connection = null;
            }
        }

        protected boolean checkConnection() throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                if (connection == null)
                {
                    try
                    {
                        connection = url.openConnection();
                        connection.setUseCaches(useCaches);
                        connection.setConnectTimeout(this.connectTimeout);
                    }
                    catch (IOException e)
                    {
                        LOG.trace("IGNORED", e);
                    }
                }
                return connection != null;
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
                return URIUtil.addPath(parent, path);
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
            boolean ret = false;
            try (AutoLock l = lock.lock())
            {
                if (checkConnection())
                {
                    if (in == null)
                        in = connection.getInputStream();
                    ret = in != null;
                }
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
            }
            return ret;
        }

        @Override
        public InputStream newInputStream() throws IOException
        {
            try (AutoLock l = lock.lock())
            {
                if (!checkConnection())
                    throw new IOException("Invalid resource");

                try
                {
                    if (in != null)
                    {
                        InputStream stream = in;
                        in = null;
                        return stream;
                    }
                    return connection.getInputStream();
                }
                finally
                {
                    connection = null;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Connection nulled");
                }
            }
        }

        @Override
        public Instant lastModified()
        {
            try (AutoLock l = lock.lock())
            {
                if (!checkConnection())
                    throw new IOException("Invalid resource");

                return Instant.ofEpochMilli(connection.getLastModified());
            }
            catch (IOException e)
            {
                return Instant.EPOCH;
            }
        }

        @Override
        public long length()
        {
            try (AutoLock l = lock.lock())
            {
                if (!checkConnection())
                    throw new IOException("Invalid resource");

                return connection.getContentLengthLong();
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
