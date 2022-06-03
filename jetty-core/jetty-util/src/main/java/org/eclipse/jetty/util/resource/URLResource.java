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
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * URL resource class.
 */
public class URLResource extends Resource
{
    private static final Logger LOG = LoggerFactory.getLogger(URLResource.class);

    protected final AutoLock _lock = new AutoLock();
    protected final URL _url;
    protected final String _urlString;
    protected URLConnection _connection;
    protected InputStream _in = null;
    transient boolean _useCaches = Resource.__defaultUseCaches;

    protected URLResource(URL url, URLConnection connection)
    {
        _url = url;
        _urlString = _url.toExternalForm();
        _connection = connection;
    }

    protected URLResource(URL url, URLConnection connection, boolean useCaches)
    {
        this(url, connection);
        _useCaches = useCaches;
    }

    protected boolean checkConnection()
    {
        try (AutoLock l = _lock.lock())
        {
            if (_connection == null)
            {
                try
                {
                    _connection = _url.openConnection();
                    _connection.setUseCaches(_useCaches);
                }
                catch (IOException e)
                {
                    LOG.trace("IGNORED", e);
                }
            }
            return _connection != null;
        }
    }

    /**
     * Release any resources held by the resource.
     */
    @Override
    public void close()
    {
        try (AutoLock l = _lock.lock())
        {
            if (_in != null)
            {
                try
                {
                    _in.close();
                }
                catch (IOException e)
                {
                    LOG.trace("IGNORED", e);
                }
                _in = null;
            }

            if (_connection != null)
                _connection = null;
        }
    }

    /**
     * Returns true if the represented resource exists.
     */
    @Override
    public boolean exists()
    {
        try
        {
            try (AutoLock l = _lock.lock())
            {
                if (checkConnection() && _in == null)
                    _in = _connection.getInputStream();
            }
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
        }
        return _in != null;
    }

    @Override
    public boolean isDirectory()
    {
        return exists() && _urlString.endsWith("/");
    }

    /**
     * Returns the last modified time
     */
    @Override
    public long lastModified()
    {
        if (checkConnection())
            return _connection.getLastModified();
        return -1;
    }

    /**
     * Return the length of the resource
     */
    @Override
    public long length()
    {
        if (checkConnection())
            return _connection.getContentLength();
        return -1;
    }

    /**
     * Returns a URI representing the given resource
     */
    @Override
    public URI getURI()
    {
        try
        {
            return _url.toURI();
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the name of the resource
     */
    @Override
    public String getName()
    {
        return _url.toExternalForm();
    }

    /**
     * Returns an input stream to the resource. The underlying
     * url connection will be nulled out to prevent re-use.
     */
    @Override
    public InputStream getInputStream() throws IOException
    {
        return getInputStream(true); //backwards compatibility
    }

    /**
     * Returns an input stream to the resource, optionally nulling
     * out the underlying url connection. If the connection is not
     * nulled out, a subsequent call to getInputStream() may return
     * an existing and already in-use input stream - this depends on
     * the url protocol. Eg JarURLConnection does not reuse inputstreams.
     *
     * @param resetConnection if true the connection field is set to null
     * @return the inputstream for this resource
     * @throws IOException if unable to open the input stream
     */
    protected InputStream getInputStream(boolean resetConnection) throws IOException
    {
        try (AutoLock l = _lock.lock())
        {
            if (!checkConnection())
                throw new IOException("Invalid resource");

            try
            {
                if (_in != null)
                {
                    InputStream in = _in;
                    _in = null;
                    return in;
                }
                return _connection.getInputStream();
            }
            finally
            {
                if (resetConnection)
                {
                    _connection = null;
                    if (LOG.isDebugEnabled())
                        LOG.debug("Connection nulled");
                }
            }
        }
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return null;
    }

    /**
     * Deletes the given resource
     */
    @Override
    public boolean delete()
        throws SecurityException
    {
        throw new SecurityException("Delete not supported");
    }

    /**
     * Rename the given resource
     */
    @Override
    public boolean renameTo(Resource dest)
        throws SecurityException
    {
        throw new SecurityException("RenameTo not supported");
    }

    /**
     * Returns a list of resource names contained in the given resource
     */
    @Override
    public String[] list()
    {
        return null;
    }

    /**
     * Returns the resource contained inside the current resource with the
     * given name
     */
    @Override
    public Resource resolve(String subPath) throws IOException
    {
        // Check that the path is within the root,
        // but use the original path to create the
        // resource, to preserve aliasing.
        if (URIUtil.canonicalPath(subPath) == null)
            throw new MalformedURLException(subPath);

        return newResource(URIUtil.addEncodedPaths(_url.toExternalForm(), URIUtil.encodePath(subPath)), _useCaches);
    }

    @Override
    public String toString()
    {
        return _urlString;
    }

    @Override
    public int hashCode()
    {
        return _urlString.hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof URLResource && _urlString.equals(((URLResource)o)._urlString);
    }

    public boolean getUseCaches()
    {
        return _useCaches;
    }

    @Override
    public Path getPath()
    {
        try
        {
            return Paths.get(_url.toURI());
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isContainedIn(Resource containingResource) throws MalformedURLException
    {
        return false;
    }
}
