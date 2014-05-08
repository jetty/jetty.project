//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.StandardOpenOption;
import java.security.Permission;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** File Resource.
 *
 * Handle resources of implied or explicit file type.
 * This class can check for aliasing in the filesystem (eg case
 * insensitivity).  By default this is turned on, or it can be controlled 
 * by calling the static method @see FileResource#setCheckAliases(boolean)
 * 
 */
public class FileResource extends Resource
{
    private static final Logger LOG = Log.getLogger(FileResource.class);

    /* ------------------------------------------------------------ */
    private final File _file;
    private final String _uri;
    private final URI _alias;
    
    /* -------------------------------------------------------- */
    public FileResource(URL url)
        throws IOException, URISyntaxException
    {
        File file;
        try
        {
            // Try standard API to convert URL to file.
            file =new File(url.toURI());
        }
        catch (URISyntaxException e) 
        {
            throw e;
        }
        catch (Exception e)
        {
            if (!url.toString().startsWith("file:"))
                throw new IllegalArgumentException("!file:");
            
            LOG.ignore(e);
            try
            {
                // Assume that File.toURL produced unencoded chars. So try encoding them.
                String file_url="file:"+URIUtil.encodePath(url.toString().substring(5));           
                URI uri = new URI(file_url);
                if (uri.getAuthority()==null) 
                    file = new File(uri);
                else
                    file = new File("//"+uri.getAuthority()+URIUtil.decodePath(url.getFile()));
            }
            catch (Exception e2)
            {
                LOG.ignore(e2);
                // Still can't get the file.  Doh! try good old hack!
                URLConnection connection=url.openConnection();
                Permission perm = connection.getPermission();
                file = new File(perm==null?url.getFile():perm.getName());
            }
        }
        
        _file=file;
        _uri=normalizeURI(_file,url.toURI());
        _alias=checkAlias(_file);
    }

    /* -------------------------------------------------------- */
    public FileResource(URI uri)
    {
        File file=new File(uri);
        _file=file;
        URI file_uri=_file.toURI();
        _uri=normalizeURI(_file,uri);
        
        if (!_uri.equals(file_uri.toString()))
        {
            // URI and File URI are different.  Is it just an encoding difference?
            if (!file_uri.toString().equals(URIUtil.decodePath(uri.toString())))
                 _alias=_file.toURI();
            else
                _alias=checkAlias(_file);
        }
        else
            _alias=checkAlias(_file);
    }

    /* -------------------------------------------------------- */
    FileResource(File file)
    {
        _file=file;
        _uri=normalizeURI(_file,_file.toURI());
        _alias=checkAlias(_file);
    }

    /* -------------------------------------------------------- */
    private static String normalizeURI(File file, URI uri)
    {
        String u =uri.toASCIIString();
        if (file.isDirectory())
        {
            if(!u.endsWith("/"))
                u+="/";
        } 
        else if (file.exists() && u.endsWith("/"))
            u=u.substring(0,u.length()-1);
        return u;
    }

    /* -------------------------------------------------------- */
    private static URI checkAlias(File file)
    {
        try
        {
            String abs=file.getAbsolutePath();
            String can=file.getCanonicalPath();

            if (!abs.equals(can))
            {
                LOG.debug("ALIAS abs={} can={}",abs,can);

                URI alias=new File(can).toURI();
                // Have to encode the path as File.toURI does not!
                return new URI("file://"+URIUtil.encodePath(alias.getPath()));  
            }
        }
        catch(Exception e)
        {
            LOG.warn("bad alias for {}: {}",file,e.toString());
            LOG.debug(e);
            try
            {
                return new URI("http://eclipse.org/bad/canonical/alias");
            }
            catch(Exception e2)
            {
                LOG.ignore(e2);
                throw new RuntimeException(e);
            }
        }

        return null;
    }
    
    /* -------------------------------------------------------- */
    @Override
    public Resource addPath(String path)
        throws IOException,MalformedURLException
    {
        path = org.eclipse.jetty.util.URIUtil.canonicalPath(path);

        if (path==null)
            throw new MalformedURLException();   
        
        if ("/".equals(path))
            return this;
        
        path=URIUtil.encodePath(path);
        // The encoded path should be a suffix of the resource (give or take a directory / )
        URI uri;
        try
        {
            if (_file.isDirectory())
            {
                // treat all paths being added as relative
                uri=new URI(URIUtil.addPaths(_uri,path));
            }
            else
            {
                uri=new URI(_uri+path);
            }
        }
        catch(final URISyntaxException e)
        {
            throw new MalformedURLException(){{initCause(e);}};
        }

        return new FileResource(uri);
    }
   
    
    /* ------------------------------------------------------------ */
    @Override
    public URI getAlias()
    {
        return _alias;
    }
    
    /* -------------------------------------------------------- */
    /**
     * Returns true if the resource exists.
     */
    @Override
    public boolean exists()
    {
        return _file.exists();
    }
        
    /* -------------------------------------------------------- */
    /**
     * Returns the last modified time
     */
    @Override
    public long lastModified()
    {
        return _file.lastModified();
    }

    /* -------------------------------------------------------- */
    /**
     * Returns true if the resource is a container/directory.
     */
    @Override
    public boolean isDirectory()
    {
        return _file.exists() && _file.isDirectory() || _uri.endsWith("/");
    }

    /* --------------------------------------------------------- */
    /**
     * Return the length of the resource
     */
    @Override
    public long length()
    {
        return _file.length();
    }
        

    /* --------------------------------------------------------- */
    /**
     * Returns the name of the resource
     */
    @Override
    public String getName()
    {
        return _file.getAbsolutePath();
    }
        
    /* ------------------------------------------------------------ */
    /**
     * Returns an File representing the given resource or NULL if this
     * is not possible.
     */
    @Override
    public File getFile()
    {
        return _file;
    }
        
    /* --------------------------------------------------------- */
    /**
     * Returns an input stream to the resource
     */
    @Override
    public InputStream getInputStream() throws IOException
    {
        return new FileInputStream(_file);
    }

    /* ------------------------------------------------------------ */
    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return FileChannel.open(_file.toPath(),StandardOpenOption.READ);
    }
        
    /* --------------------------------------------------------- */
    /**
     * Deletes the given resource
     */
    @Override
    public boolean delete()
        throws SecurityException
    {
        return _file.delete();
    }

    /* --------------------------------------------------------- */
    /**
     * Rename the given resource
     */
    @Override
    public boolean renameTo( Resource dest)
        throws SecurityException
    {
        if( dest instanceof FileResource)
            return _file.renameTo( ((FileResource)dest)._file);
        else
            return false;
    }

    /* --------------------------------------------------------- */
    /**
     * Returns a list of resources contained in the given resource
     */
    @Override
    public String[] list()
    {
        String[] list =_file.list();
        if (list==null)
            return null;
        for (int i=list.length;i-->0;)
        {
            if (new File(_file,list[i]).isDirectory() &&
                !list[i].endsWith("/"))
                list[i]+="/";
        }
        return list;
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * @param o
     * @return <code>true</code> of the object <code>o</code> is a {@link FileResource} pointing to the same file as this resource. 
     */
    @Override
    public boolean equals( Object o)
    {
        if (this == o)
            return true;

        if (null == o || ! (o instanceof FileResource))
            return false;

        FileResource f=(FileResource)o;
        return f._file == _file || (null != _file && _file.equals(f._file));
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the hashcode.
     */
    @Override
    public int hashCode()
    {
       return null == _file ? super.hashCode() : _file.hashCode();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void copyTo(File destination)
        throws IOException
    {
        if (isDirectory())
        {
            IO.copyDir(getFile(),destination);
        }
        else
        {
            if (destination.exists())
                throw new IllegalArgumentException(destination+" exists");
            IO.copy(getFile(),destination);
        }
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
    public URL getURL()
    {
        try
        {
            return new URL(_uri);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalStateException(e);
        }
    }
    
    @Override
    public URI getURI()
    {
        return _file.toURI();
    }

    @Override
    public String toString()
    {
        return _uri;
    }

}
