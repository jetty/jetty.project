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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** 
 * Abstract resource class.
 */
public abstract class Resource implements ResourceFactory
{
    private static final Logger LOG = Log.getLogger(Resource.class);
    public static boolean __defaultUseCaches = true;
    volatile Object _associate;

    /* ------------------------------------------------------------ */
    /**
     * Change the default setting for url connection caches.
     * Subsequent URLConnections will use this default.
     * @param useCaches
     */
    public static void setDefaultUseCaches (boolean useCaches)
    {
        __defaultUseCaches=useCaches;
    }

    /* ------------------------------------------------------------ */
    public static boolean getDefaultUseCaches ()
    {
        return __defaultUseCaches;
    }
    
    /* ------------------------------------------------------------ */
    /** Construct a resource from a uri.
     * @param uri A URI.
     * @return A Resource object.
     * @throws IOException Problem accessing URI
     */
    public static Resource newResource(URI uri)
        throws IOException
    {
        return newResource(uri.toURL());
    }
    
    /* ------------------------------------------------------------ */
    /** Construct a resource from a url.
     * @param url A URL.
     * @return A Resource object.
     * @throws IOException Problem accessing URL
     */
    public static Resource newResource(URL url)
        throws IOException
    {
        return newResource(url, __defaultUseCaches);
    }
    
    /* ------------------------------------------------------------ */   
    /**
     * Construct a resource from a url.
     * @param url the url for which to make the resource
     * @param useCaches true enables URLConnection caching if applicable to the type of resource
     * @return
     */
    static Resource newResource(URL url, boolean useCaches)
    {
        if (url==null)
            return null;

        String url_string=url.toExternalForm();
        if( url_string.startsWith( "file:"))
        {
            try
            {
                FileResource fileResource= new FileResource(url);
                return fileResource;
            }
            catch(Exception e)
            {
                LOG.debug(Log.EXCEPTION,e);
                return new BadResource(url,e.toString());
            }
        }
        else if( url_string.startsWith( "jar:file:"))
        {
            return new JarFileResource(url, useCaches);
        }
        else if( url_string.startsWith( "jar:"))
        {
            return new JarResource(url, useCaches);
        }

        return new URLResource(url,null,useCaches);
    }

    
    
    /* ------------------------------------------------------------ */
    /** Construct a resource from a string.
     * @param resource A URL or filename.
     * @return A Resource object.
     */
    public static Resource newResource(String resource)
        throws MalformedURLException, IOException
    {
        return newResource(resource, __defaultUseCaches);
    }
    
    /* ------------------------------------------------------------ */
    /** Construct a resource from a string.
     * @param resource A URL or filename.
     * @param useCaches controls URLConnection caching
     * @return A Resource object.
     */
    public static Resource newResource (String resource, boolean useCaches)       
    throws MalformedURLException, IOException
    {
        URL url=null;
        try
        {
            // Try to format as a URL?
            url = new URL(resource);
        }
        catch(MalformedURLException e)
        {
            if(!resource.startsWith("ftp:") &&
               !resource.startsWith("file:") &&
               !resource.startsWith("jar:"))
            {
                try
                {
                    // It's a file.
                    if (resource.startsWith("./"))
                        resource=resource.substring(2);
                    
                    File file=new File(resource).getCanonicalFile();
                    url=Resource.toURL(file);            
                    
                    URLConnection connection=url.openConnection();
                    connection.setUseCaches(useCaches);
                    return new FileResource(url,connection,file);
                }
                catch(Exception e2)
                {
                    LOG.debug(Log.EXCEPTION,e2);
                    throw e;
                }
            }
            else
            {
                LOG.warn("Bad Resource: "+resource);
                throw e;
            }
        }

        return newResource(url);
    }

    /* ------------------------------------------------------------ */
    public static Resource newResource (File file)
    throws MalformedURLException, IOException
    {
        file = file.getCanonicalFile();
        URL url = Resource.toURL(file);

        URLConnection connection = url.openConnection();
        FileResource fileResource = new FileResource(url, connection, file);
        return fileResource;
    }

    /* ------------------------------------------------------------ */
    /** Construct a system resource from a string.
     * The resource is tried as classloader resource before being
     * treated as a normal resource.
     * @param resource Resource as string representation 
     * @return The new Resource
     * @throws IOException Problem accessing resource.
     */
    public static Resource newSystemResource(String resource)
        throws IOException
    {
        URL url=null;
        // Try to format as a URL?
        ClassLoader loader=Thread.currentThread().getContextClassLoader();
        if (loader!=null)
        {
            try
            {
                url = loader.getResource(resource);
                if (url == null && resource.startsWith("/"))
                    url = loader.getResource(resource.substring(1));
            }
            catch (IllegalArgumentException e)
            {
                // Catches scenario where a bad Windows path like "C:\dev" is
                // improperly escaped, which various downstream classloaders
                // tend to have a problem with
                url = null;
            }
        }
        if (url==null)
        {
            loader=Resource.class.getClassLoader();
            if (loader!=null)
            {
                url=loader.getResource(resource);
                if (url==null && resource.startsWith("/"))
                    url=loader.getResource(resource.substring(1));
            }
        }
        
        if (url==null)
        {
            url=ClassLoader.getSystemResource(resource);
            if (url==null && resource.startsWith("/"))
                url=ClassLoader.getSystemResource(resource.substring(1));
        }
        
        if (url==null)
            return null;
        
        return newResource(url);
    }

    /* ------------------------------------------------------------ */
    /** Find a classpath resource.
     */
    public static Resource newClassPathResource(String resource)
    {
        return newClassPathResource(resource,true,false);
    }

    /* ------------------------------------------------------------ */
    /** Find a classpath resource.
     * The {@link java.lang.Class#getResource(String)} method is used to lookup the resource. If it is not
     * found, then the {@link Loader#getResource(Class, String, boolean)} method is used.
     * If it is still not found, then {@link ClassLoader#getSystemResource(String)} is used.
     * Unlike {@link ClassLoader#getSystemResource(String)} this method does not check for normal resources.
     * @param name The relative name of the resource
     * @param useCaches True if URL caches are to be used.
     * @param checkParents True if forced searching of parent Classloaders is performed to work around 
     * loaders with inverted priorities
     * @return Resource or null
     */
    public static Resource newClassPathResource(String name,boolean useCaches,boolean checkParents)
    {
        URL url=Resource.class.getResource(name);
        
        if (url==null)
            url=Loader.getResource(Resource.class,name,checkParents);
        if (url==null)
            return null;
        return newResource(url,useCaches);
    }
    
    /* ------------------------------------------------------------ */
    public static boolean isContainedIn (Resource r, Resource containingResource) throws MalformedURLException
    {
        return r.isContainedIn(containingResource);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void finalize()
    {
        release();
    }
    
    /* ------------------------------------------------------------ */
    public abstract boolean isContainedIn (Resource r) throws MalformedURLException;
    
    
    /* ------------------------------------------------------------ */
    /** Release any temporary resources held by the resource.
     */
    public abstract void release();
    

    /* ------------------------------------------------------------ */
    /**
     * Returns true if the respresened resource exists.
     */
    public abstract boolean exists();
    

    /* ------------------------------------------------------------ */
    /**
     * Returns true if the respresenetd resource is a container/directory.
     * If the resource is not a file, resources ending with "/" are
     * considered directories.
     */
    public abstract boolean isDirectory();

    /* ------------------------------------------------------------ */
    /**
     * Returns the last modified time
     */
    public abstract long lastModified();


    /* ------------------------------------------------------------ */
    /**
     * Return the length of the resource
     */
    public abstract long length();
    

    /* ------------------------------------------------------------ */
    /**
     * Returns an URL representing the given resource
     */
    public abstract URL getURL();

    /* ------------------------------------------------------------ */
    /**
     * Returns an URI representing the given resource
     */
    public URI getURI()
    {
        try
        {
            return getURL().toURI();
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    

    /* ------------------------------------------------------------ */
    /**
     * Returns an File representing the given resource or NULL if this
     * is not possible.
     */
    public abstract File getFile()
        throws IOException;
    

    /* ------------------------------------------------------------ */
    /**
     * Returns the name of the resource
     */
    public abstract String getName();
    

    /* ------------------------------------------------------------ */
    /**
     * Returns an input stream to the resource
     */
    public abstract InputStream getInputStream()
        throws java.io.IOException;

    /* ------------------------------------------------------------ */
    /**
     * Returns an output stream to the resource
     */
    public abstract OutputStream getOutputStream()
        throws java.io.IOException, SecurityException;
    
    /* ------------------------------------------------------------ */
    /**
     * Deletes the given resource
     */
    public abstract boolean delete()
        throws SecurityException;
    
    /* ------------------------------------------------------------ */
    /**
     * Rename the given resource
     */
    public abstract boolean renameTo( Resource dest)
        throws SecurityException;
    
    /* ------------------------------------------------------------ */
    /**
     * Returns a list of resource names contained in the given resource
     * The resource names are not URL encoded.
     */
    public abstract String[] list();

    /* ------------------------------------------------------------ */
    /**
     * Returns the resource contained inside the current resource with the
     * given name.
     * @param path The path segment to add, which should be encoded by the
     * encode method. 
     */
    public abstract Resource addPath(String path)
        throws IOException,MalformedURLException;

    /* ------------------------------------------------------------ */
    /** Get a resource from withing this resource.
     * <p>
     * This method is essentially an alias for {@link #addPath(String)}, but without checked exceptions.
     * This method satisfied the {@link ResourceFactory} interface.
     * @see org.eclipse.jetty.util.resource.ResourceFactory#getResource(java.lang.String)
     */
    public Resource getResource(String path)
    {
        try
        {
            return addPath(path);
        }
        catch(Exception e)
        {
            LOG.debug(e);
            return null;
        }
    }

    /* ------------------------------------------------------------ */
    /** Encode according to this resource type.
     * The default implementation calls URI.encodePath(uri)
     * @param uri 
     * @return String encoded for this resource type.
     */
    public String encode(String uri)
    {
        return URIUtil.encodePath(uri);
    }
        
    /* ------------------------------------------------------------ */
    public Object getAssociate()
    {
        return _associate;
    }

    /* ------------------------------------------------------------ */
    public void setAssociate(Object o)
    {
        _associate=o;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The canonical Alias of this resource or null if none.
     */
    public URL getAlias()
    {
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the resource list as a HTML directory listing.
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @return String of HTML
     */
    public String getListHTML(String base,boolean parent)
        throws IOException
    {
        base=URIUtil.canonicalPath(base);
        if (base==null || !isDirectory())
            return null;
        
        String[] ls = list();
        if (ls==null)
            return null;
        Arrays.sort(ls);
        
        String decodedBase = URIUtil.decodePath(base);
        String title = "Directory: "+deTag(decodedBase);

        StringBuilder buf=new StringBuilder(4096);
        buf.append("<HTML><HEAD>");
        buf.append("<LINK HREF=\"").append("jetty-dir.css").append("\" REL=\"stylesheet\" TYPE=\"text/css\"/><TITLE>");
        buf.append(title);
        buf.append("</TITLE></HEAD><BODY>\n<H1>");
        buf.append(title);
        buf.append("</H1>\n<TABLE BORDER=0>\n");
        
        if (parent)
        {
            buf.append("<TR><TD><A HREF=\"");
            buf.append(URIUtil.addPaths(base,"../"));
            buf.append("\">Parent Directory</A></TD><TD></TD><TD></TD></TR>\n");
        }
        
        String encodedBase = hrefEncodeURI(base);
        
        DateFormat dfmt=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                                                       DateFormat.MEDIUM);
        for (int i=0 ; i< ls.length ; i++)
        {
            Resource item = addPath(ls[i]);
            
            buf.append("\n<TR><TD><A HREF=\"");
            String path=URIUtil.addPaths(encodedBase,URIUtil.encodePath(ls[i]));
            
            buf.append(path);
            
            if (item.isDirectory() && !path.endsWith("/"))
                buf.append(URIUtil.SLASH);
            
            // URIUtil.encodePath(buf,path);
            buf.append("\">");
            buf.append(deTag(ls[i]));
            buf.append("&nbsp;");
            buf.append("</A></TD><TD ALIGN=right>");
            buf.append(item.length());
            buf.append(" bytes&nbsp;</TD><TD>");
            buf.append(dfmt.format(new Date(item.lastModified())));
            buf.append("</TD></TR>");
        }
        buf.append("</TABLE>\n");
	buf.append("</BODY></HTML>\n");
        
        return buf.toString();
    }
    
    /**
     * Encode any characters that could break the URI string in an HREF.
     * Such as <a href="/path/to;<script>Window.alert("XSS"+'%20'+"here");</script>">Link</a>
     * 
     * The above example would parse incorrectly on various browsers as the "<" or '"' characters
     * would end the href attribute value string prematurely.
     * 
     * @param raw the raw text to encode.
     * @return the defanged text.
     */
    private static String hrefEncodeURI(String raw) 
    {
        StringBuffer buf = null;

        loop:
        for (int i=0;i<raw.length();i++)
        {
            char c=raw.charAt(i);
            switch(c)
            {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf=new StringBuffer(raw.length()<<1);
                    break loop;
            }
        }
        if (buf==null)
            return raw;

        for (int i=0;i<raw.length();i++)
        {
            char c=raw.charAt(i);       
            switch(c)
            {
              case '"':
                  buf.append("%22");
                  continue;
              case '\'':
                  buf.append("%27");
                  continue;
              case '<':
                  buf.append("%3C");
                  continue;
              case '>':
                  buf.append("%3E");
                  continue;
              default:
                  buf.append(c);
                  continue;
            }
        }

        return buf.toString();
    }
    
    private static String deTag(String raw) 
    {
        return StringUtil.replace( StringUtil.replace(raw,"<","&lt;"), ">", "&gt;");
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * @param out 
     * @param start First byte to write
     * @param count Bytes to write or -1 for all of them.
     */
    public void writeTo(OutputStream out,long start,long count)
        throws IOException
    {
        InputStream in = getInputStream();
        try
        {
            in.skip(start);
            if (count<0)
                IO.copy(in,out);
            else
                IO.copy(in,out,count);
        }
        finally
        {
            in.close();
        }
    }    
    
    /* ------------------------------------------------------------ */
    public void copyTo(File destination)
        throws IOException
    {
        if (destination.exists())
            throw new IllegalArgumentException(destination+" exists");
        writeTo(new FileOutputStream(destination),0,-1);
    }

    /* ------------------------------------------------------------ */
    public String getWeakETag()
    {
        try
        {
            StringBuilder b = new StringBuilder(32);
            b.append("W/\"");
            
            String name=getName();
            int length=name.length();
            long lhash=0;
            for (int i=0; i<length;i++)
                lhash=31*lhash+name.charAt(i);
            
            B64Code.encode(lastModified()^lhash,b);
            B64Code.encode(length()^lhash,b);
            b.append('"');
            return b.toString();
        } 
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Generate a properly encoded URL from a {@link File} instance.
     * @param file Target file. 
     * @return URL of the target file.
     * @throws MalformedURLException 
     */
    public static URL toURL(File file) throws MalformedURLException
    {
        return file.toURI().toURL();
    }
}
