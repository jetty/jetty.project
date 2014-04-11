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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
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
public class FileResource extends URLResource
{
    private static final Logger LOG = Log.getLogger(FileResource.class);
    private static boolean __checkAliases = true;

    /* ------------------------------------------------------------ */
    private File _file;
    private transient URL _alias=null;
    private transient boolean _aliasChecked=false;

    /* ------------------------------------------------------------------------------- */
    /** setCheckAliases.
     * @param checkAliases True of resource aliases are to be checked for (eg case insensitivity or 8.3 short names) and treated as not found.
     */
    public static void setCheckAliases(boolean checkAliases)
    {
        __checkAliases=checkAliases;
    }

    /* ------------------------------------------------------------------------------- */
    /** getCheckAliases.
     * @return True of resource aliases are to be checked for (eg case insensitivity or 8.3 short names) and treated as not found.
     */
    public static boolean getCheckAliases()
    {
        return __checkAliases;
    }
    
    /* -------------------------------------------------------- */
    public FileResource(URL url)
        throws IOException, URISyntaxException
    {
        super(url,null);

        try
        {
            // Try standard API to convert URL to file.
            _file =new File(new URI(url.toString()));
        }
        catch (URISyntaxException e) 
        {
            throw e;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
            try
            {
                // Assume that File.toURL produced unencoded chars. So try
                // encoding them.
                String file_url="file:"+URIUtil.encodePath(url.toString().substring(5));           
                URI uri = new URI(file_url);
                if (uri.getAuthority()==null) 
                    _file = new File(uri);
                else
                    _file = new File("//"+uri.getAuthority()+URIUtil.decodePath(url.getFile()));
            }
            catch (Exception e2)
            {
                LOG.ignore(e2);

                // Still can't get the file.  Doh! try good old hack!
                checkConnection();
                Permission perm = _connection.getPermission();
                _file = new File(perm==null?url.getFile():perm.getName());
            }
        }
        if (_file.isDirectory())
        {
            if (!_urlString.endsWith("/"))
                _urlString=_urlString+"/";
        }
        else
        {
            if (_urlString.endsWith("/"))
                _urlString=_urlString.substring(0,_urlString.length()-1);
        }

    }

    /* -------------------------------------------------------- */
    FileResource(URL url, URLConnection connection, File file)
    {
        super(url,connection);
        _file=file;
        if (_file.isDirectory() && !_urlString.endsWith("/"))
            _urlString=_urlString+"/";
    }
    
    /* -------------------------------------------------------- */
    @Override
    public Resource addPath(String path)
        throws IOException,MalformedURLException
    {
        URLResource r=null;
        String url=null;

        path = org.eclipse.jetty.util.URIUtil.canonicalPath(path);
       
        if ("/".equals(path))
            return this;
        else if (!isDirectory())
        {
            r=(FileResource)super.addPath(path);
            url=r._urlString;
        }
        else
        {
            if (path==null)
                throw new MalformedURLException();   
            
            // treat all paths being added as relative
            String rel=path;
            if (path.startsWith("/"))
                rel = path.substring(1);
            
            url=URIUtil.addPaths(_urlString,URIUtil.encodePath(rel));
            r=(URLResource)Resource.newResource(url);
        }
        
        // Check for encoding aliases
        // The encoded path should be a suffix of the resource (give or take a directory / )
        String encoded=URIUtil.encodePath(path);
        int expected=r.toString().length()-encoded.length();
        int index = r._urlString.lastIndexOf(encoded, expected);
        if (expected!=index && ((expected-1)!=index || path.endsWith("/") || !r.isDirectory()))
        {
            if (r instanceof FileResource)
            {
                ((FileResource)r)._alias=((FileResource)r)._file.getCanonicalFile().toURI().toURL();
                ((FileResource)r)._aliasChecked=true;
            }
        }                             
        return r;
    }
   
    
    /* ------------------------------------------------------------ */
    @Override
    public URL getAlias()
    {
        if (__checkAliases && !_aliasChecked)
        {
            try
            {    
                String abs=_file.getAbsolutePath();
                String can=_file.getCanonicalPath();
                
                if (abs.length()!=can.length() || !abs.equals(can))
                    _alias=Resource.toURL(new File(can));
                
                _aliasChecked=true;
                
                if (_alias!=null && LOG.isDebugEnabled())
                {
                    LOG.debug("ALIAS abs="+abs);
                    LOG.debug("ALIAS can="+can);
                }
            }
            catch(Exception e)
            {
                LOG.warn(Log.EXCEPTION,e);
                return getURL();
            }                
        }
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
     * Returns true if the respresenetd resource is a container/directory.
     */
    @Override
    public boolean isDirectory()
    {
        return _file.isDirectory();
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
        
    /* --------------------------------------------------------- */
    /**
     * Returns an output stream to the resource
     */
    @Override
    public OutputStream getOutputStream()
        throws java.io.IOException, SecurityException
    {
        return new FileOutputStream(_file);
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
    /** Encode according to this resource type.
     * File URIs are encoded.
     * @param uri URI to encode.
     * @return The uri unchanged.
     */
    @Override
    public String encode(String uri)
    {
        return uri;
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
}
