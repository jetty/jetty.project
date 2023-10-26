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
import java.lang.ref.Cleaner;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ResourceFactory} for {@link java.net.URL} based resources.
 */
public class URLResourceFactory implements ResourceFactory
{
    static Consumer<InputStream> ON_SWEEP_LISTENER;
    private int connectTimeout = 0;
    private int readTimeout = 0;
    private boolean useCaches = true;

    public URLResourceFactory()
    {
    }

    /**
     * URL Connection Connect Timeout
     *
     * @return the connect timeout
     * @see URLConnection#getConnectTimeout()
     */
    public int getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    /**
     * URL Connection Read Timeout.
     *
     * @return the read timeout
     * @see URLConnection#getReadTimeout()
     */
    public int getReadTimeout()
    {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout)
    {
        this.readTimeout = readTimeout;
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
            return new URLResource(uri, this.connectTimeout, this.readTimeout, this.useCaches);
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Bad URI: " + uri, e);
        }
    }

    private static class URLResource extends Resource
    {
        private static final Logger LOG = LoggerFactory.getLogger(URLResource.class);
        private static final Cleaner CLEANER = Cleaner.create();

        private final AutoLock lock = new AutoLock();
        private final URI uri;
        private final URL url;
        private final int connectTimeout;
        private final int readTimeout;
        private final boolean useCaches;
        private URLConnection connection;
        private InputStreamReference inputStreamReference = null;

        public URLResource(URI uri, int connectTimeout, int readTimeout, boolean useCaches) throws MalformedURLException
        {
            this.uri = uri;
            this.url = uri.toURL();
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.useCaches = useCaches;
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
                        connection.setConnectTimeout(connectTimeout);
                        connection.setReadTimeout(readTimeout);
                        connection.setUseCaches(useCaches);
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
        public boolean isContainedIn(Resource container)
        {
            // compare starting URIs?
            return false;
        }

        @Override
        public boolean isDirectory()
        {
            return exists() && uri.getSchemeSpecificPart().endsWith("/");
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
            if (URIUtil.isNotNormalWithinSelf(subUriPath))
                throw new IllegalArgumentException(subUriPath);

            if ("/".equals(subUriPath))
                return this;

            URI newURI = URIUtil.addPath(uri, subUriPath);
            try
            {
                return new URLResource(newURI, this.connectTimeout, this.readTimeout, this.useCaches);
            }
            catch (MalformedURLException e)
            {
                return null;
            }
        }

        @Override
        public boolean exists()
        {
            try (AutoLock l = lock.lock())
            {
                if (checkConnection())
                {
                    if (inputStreamReference == null || inputStreamReference.get() == null)
                    {
                        inputStreamReference = new InputStreamReference(connection.getInputStream());
                        CLEANER.register(this, inputStreamReference);
                    }
                    return true;
                }
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
            }
            return false;
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
                    if (inputStreamReference != null)
                    {
                        InputStream stream = inputStreamReference.getAndSet(null);
                        inputStreamReference = null;
                        if (stream != null)
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
        public URI getRealURI()
        {
            return getURI();
        }

        @Override
        public String toString()
        {
            return String.format("URLResource@%X(%s)", this.uri.hashCode(), this.uri.toASCIIString());
        }

        private static class InputStreamReference extends AtomicReference<InputStream> implements Runnable
        {
            public InputStreamReference(InputStream initialValue)
            {
                super(initialValue);
            }

            @Override
            public void run()
            {
                // Called when the URLResource that held the same InputStream has been collected
                InputStream in = getAndSet(null);
                if (in != null)
                    IO.close(in);

                if (ON_SWEEP_LISTENER != null)
                    ON_SWEEP_LISTENER.accept(in);
            }
        }

    }
}
