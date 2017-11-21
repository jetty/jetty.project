//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.net.URLConnection;
import java.nio.channels.ReadableByteChannel;
import java.security.Permission;

import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** URL resource class.
 */
public class URLResource extends Resource
{
    private static final Logger LOG = Log.getLogger(URLResource.class);
    protected final URL _url;
    protected final String _urlString;
    
    protected URLConnection _connection;
    protected InputStream _in=null;
    transient boolean _useCaches = Resource.__defaultUseCaches;
    
    /* ------------------------------------------------------------ */
    protected URLResource(URL url, URLConnection connection)
    {
        _url = url;
        _urlString=_url.toExternalForm();
        _connection=connection;
    }
    
    /* ------------------------------------------------------------ */
    protected URLResource (URL url, URLConnection connection, boolean useCaches)
    {
        this (url, connection);
        _useCaches = useCaches;
    }

    /* ------------------------------------------------------------ */
    protected synchronized boolean checkConnection()
    {
        if (_connection==null)
        {
            try{
                _connection=_url.openConnection();
                _connection.setUseCaches(_useCaches);
            }
            catch(IOException e)
            {
                LOG.ignore(e);
            }
        }
        return _connection!=null;
    }

    /* ------------------------------------------------------------ */
    /** Release any resources held by the resource.
     */
    @Override
    public synchronized void close()
    {
        if (_in!=null)
        {
            try{_in.close();}catch(IOException e){LOG.ignore(e);}
            _in=null;
        }

        if (_connection!=null)
            _connection=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns true if the represented resource exists.
     */
    @Override
    public boolean exists()
    {
        try
        {
            synchronized(this)
            {
                if (checkConnection() && _in==null )
                    _in = _connection.getInputStream();
            }
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }
        return _in!=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns true if the represented resource is a container/directory.
     * If the resource is not a file, resources ending with "/" are
     * considered directories.
     */
    @Override
    public boolean isDirectory()
    {
        return exists() && _urlString.endsWith("/");
    }


    /* ------------------------------------------------------------ */
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


    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /**
     * Returns an URL representing the given resource
     */
    @Override
    public URL getURL()
    {
        return _url;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns an File representing the given resource or NULL if this
     * is not possible.
     */
    @Override
    public File getFile()
        throws IOException
    {
        return null;    
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the name of the resource
     */
    @Override
    public String getName()
    {
        return _url.toExternalForm();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Returns an input stream to the resource. The underlying 
     * url connection will be nulled out to prevent re-use.
     */
    @Override
    public synchronized InputStream getInputStream()
        throws java.io.IOException
    {
        return getInputStream (true); //backwards compatibility
    }
 
    /* ------------------------------------------------------------ */
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
    protected synchronized InputStream getInputStream(boolean resetConnection)
        throws IOException
    {
        if (!checkConnection())
            throw new IOException( "Invalid resource");

        try
        {    
            if( _in != null)
            {
                InputStream in = _in;
                _in=null;
                return in;
            }
            return _connection.getInputStream();
        }
        finally
        {
            if (resetConnection)
            {
                _connection=null;
                if (LOG.isDebugEnabled()) LOG.debug("Connection nulled");
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * Deletes the given resource
     */
    @Override
    public boolean delete()
        throws SecurityException
    {
        throw new SecurityException( "Delete not supported");
    }

    /* ------------------------------------------------------------ */
    /**
     * Rename the given resource
     */
    @Override
    public boolean renameTo( Resource dest)
        throws SecurityException
    {
        throw new SecurityException( "RenameTo not supported");
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns a list of resource names contained in the given resource
     */
    @Override
    public String[] list()
    {
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * Returns the resource contained inside the current resource with the
     * given name
     */
    @Override
    public Resource addPath(String path)
        throws IOException,MalformedURLException
    {
        if (path==null)
            return null;

        path = URIUtil.canonicalPath(path);

        return newResource(URIUtil.addEncodedPaths(_url.toExternalForm(),URIUtil.encodePath(path)), _useCaches);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return _urlString;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int hashCode()
    {
        return _urlString.hashCode();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean equals( Object o)
    {
        return o instanceof URLResource && _urlString.equals(((URLResource)o)._urlString);
    }

    /* ------------------------------------------------------------ */
    public boolean getUseCaches ()
    {
        return _useCaches;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isContainedIn (Resource containingResource) throws MalformedURLException
    {
        return false;
    }
}
