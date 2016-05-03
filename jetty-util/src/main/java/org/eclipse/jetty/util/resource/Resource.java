//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;

import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** 
 * Abstract resource class.
 * <p>
 * This class provides a resource abstraction, where a resource may be
 * a file, a URL or an entry in a jar file.
 * </p>
 */
public abstract class Resource implements ResourceFactory, Closeable
{
    private static final Logger LOG = Log.getLogger(Resource.class);
    public static boolean __defaultUseCaches = true;
    volatile Object _associate;

    /* ------------------------------------------------------------ */
    /**
     * Change the default setting for url connection caches.
     * Subsequent URLConnections will use this default.
     * @param useCaches true to enable URL connection caches, false otherwise.
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
     * @throws MalformedURLException Problem accessing URI
     */
    public static Resource newResource(URI uri)
        throws MalformedURLException
    {
        return newResource(uri.toURL());
    }
    
    /* ------------------------------------------------------------ */
    /** Construct a resource from a url.
     * @param url A URL.
     * @return A Resource object.
     */
    public static Resource newResource(URL url)
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
                return new PathResource(url);
            }
            catch(Exception e)
            {
                LOG.warn(e.toString());
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
     * @throws MalformedURLException Problem accessing URI
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
     * @throws MalformedURLException Problem accessing URI
     */
    public static Resource newResource(String resource, boolean useCaches)       
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
                    return new PathResource(file);
                }
                catch(IOException e2)
                {
                    e2.addSuppressed(e);
                    throw e2;
                }
            }
            else
            {
                LOG.warn("Bad Resource: "+resource);
                throw e;
            }
        }

        return newResource(url, useCaches);
    }

    /* ------------------------------------------------------------ */
    public static Resource newResource(File file)
    {
        return new PathResource(file.toPath());
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
                LOG.ignore(e);
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
     * @param resource the relative name of the resource
     * @return Resource or null
     */
    public static Resource newClassPathResource(String resource)
    {
        return newClassPathResource(resource,true,false);
    }

    /* ------------------------------------------------------------ */
    /** Find a classpath resource.
     * The {@link java.lang.Class#getResource(String)} method is used to lookup the resource. If it is not
     * found, then the {@link Loader#getResource(String)} method is used.
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
            url=Loader.getResource(name);
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
        close();
    }
    
    /* ------------------------------------------------------------ */
    public abstract boolean isContainedIn (Resource r) throws MalformedURLException;
    
    
    /* ------------------------------------------------------------ */
    /** Release any temporary resources held by the resource.
     * @deprecated use {@link #close()}
     */
    public final void release()
    {
        close();
    }

    /* ------------------------------------------------------------ */
    /** Release any temporary resources held by the resource.
     */
    @Override
    public abstract void close();

    /* ------------------------------------------------------------ */
    /**
     * @return true if the represented resource exists.
     */
    public abstract boolean exists();
    

    /* ------------------------------------------------------------ */
    /**
     * @return true if the represented resource is a container/directory.
     * if the resource is not a file, resources ending with "/" are
     * considered directories.
     */
    public abstract boolean isDirectory();

    /* ------------------------------------------------------------ */
    /**
     * Time resource was last modified.
     * 
     * @return the last modified time as milliseconds since unix epoch
     */
    public abstract long lastModified();


    /* ------------------------------------------------------------ */
    /**
     * Length of the resource.
     * 
     * @return the length of the resource
     */
    public abstract long length();
    

    /* ------------------------------------------------------------ */
    /**
     * URL representing the resource.
     * 
     * @return an URL representing the given resource
     * @deprecated use {{@link #getURI()}.toURL() instead.
     */
    @Deprecated
    public abstract URL getURL();

    /* ------------------------------------------------------------ */
    /**
     * URI representing the resource.
     * 
     * @return an URI representing the given resource
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
     * File representing the given resource.
     * 
     * @return an File representing the given resource or NULL if this
     * is not possible.
     * @throws IOException if unable to get the resource due to permissions 
     */
    public abstract File getFile()
        throws IOException;
    

    /* ------------------------------------------------------------ */
    /**
     * The name of the resource.
     * 
     * @return the name of the resource
     */
    public abstract String getName();
    

    /* ------------------------------------------------------------ */
    /**
     * Input stream to the resource
     * 
     * @return an input stream to the resource
     * @throws IOException if unable to open the input stream
     */
    public abstract InputStream getInputStream()
        throws IOException;
    
    /* ------------------------------------------------------------ */
    /**
     * Readable ByteChannel for the resource.
     * 
     * @return an readable bytechannel to the resource or null if one is not available.
     * @throws IOException if unable to open the readable bytechannel for the resource.
     */
    public abstract ReadableByteChannel getReadableByteChannel()
        throws IOException;

    /* ------------------------------------------------------------ */
    /**
     * Deletes the given resource
     * @return true if resource was found and successfully deleted, false if resource didn't exist or was unable to
     * be deleted.
     * @throws SecurityException if unable to delete due to permissions 
     */
    public abstract boolean delete()
        throws SecurityException;
    
    /* ------------------------------------------------------------ */
    /**
     * Rename the given resource
     * @param dest the destination name for the resource
     * @return true if the resource was renamed, false if the resource didn't exist or was unable to be renamed.
     * @throws SecurityException if unable to rename due to permissions
     */
    public abstract boolean renameTo(Resource dest)
        throws SecurityException;
    
    /* ------------------------------------------------------------ */
    /**
     * list of resource names contained in the given resource.
     * 
     * @return a list of resource names contained in the given resource.
     * Note: The resource names are not URL encoded.
     */
    public abstract String[] list();

    /* ------------------------------------------------------------ */
    /**
     * Returns the resource contained inside the current resource with the
     * given name.
     * @param path The path segment to add, which is not encoded
     * @return the Resource for the resolved path within this Resource.
     * @throws IOException if unable to resolve the path
     * @throws MalformedURLException if the resolution of the path fails because the input path parameter is malformed.
     */
    public abstract Resource addPath(String path)
        throws IOException,MalformedURLException;

    /* ------------------------------------------------------------ */
    /** Get a resource from within this resource.
     * <p>
     * This method is essentially an alias for {@link #addPath(String)}, but without checked exceptions.
     * This method satisfied the {@link ResourceFactory} interface.
     * @see org.eclipse.jetty.util.resource.ResourceFactory#getResource(java.lang.String)
     */
    @Override
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
    /** 
     * @param uri the uri to encode
     * @return null (this is deprecated)
     * @deprecated use {@link URIUtil} or {@link UrlEncoded} instead
     */
    @Deprecated
    public String encode(String uri)
    {
        return null;
    }
        
    /* ------------------------------------------------------------ */
    // FIXME: this appears to not be used
    @SuppressWarnings("javadoc")
    public Object getAssociate()
    {
        return _associate;
    }

    /* ------------------------------------------------------------ */
    // FIXME: this appear to not be used
    @SuppressWarnings("javadoc")
    public void setAssociate(Object o)
    {
        _associate=o;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if this Resource is an alias to another real Resource
     */
    public boolean isAlias()
    {
        return getAlias()!=null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The canonical Alias of this resource or null if none.
     */
    public URI getAlias()
    {
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the resource list as a HTML directory listing.
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @return String of HTML
     * @throws IOException if unable to get the list of resources as HTML
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
        return StringUtil.sanitizeXmlString(raw);
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * @param out the output stream to write to 
     * @param start First byte to write
     * @param count Bytes to write or -1 for all of them.
     * @throws IOException if unable to copy the Resource to the output
     */
    public void writeTo(OutputStream out,long start,long count)
        throws IOException
    {
        try (InputStream in = getInputStream())
        {
            in.skip(start);
            if (count<0)
                IO.copy(in,out);
            else
                IO.copy(in,out,count);
        }
    }    
    
    /* ------------------------------------------------------------ */
    /**
     * Copy the Resource to the new destination file.
     * <p>
     * Will not replace existing destination file.
     * 
     * @param destination the destination file to create
     * @throws IOException if unable to copy the resource
     */
    public void copyTo(File destination)
        throws IOException
    {
        if (destination.exists())
            throw new IllegalArgumentException(destination + " exists");
        
        try (OutputStream out = new FileOutputStream(destination))
        {
            writeTo(out,0,-1);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Generate a weak ETag reference for this Resource.
     * 
     * @return the weak ETag reference for this resource.
     */
    public String getWeakETag()
    {
        return getWeakETag("");
    }
    
    public String getWeakETag(String suffix)
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
            b.append(suffix);
            b.append('"');
            return b.toString();
        } 
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    public Collection<Resource> getAllResources()
    {
        try
        {
            ArrayList<Resource> deep=new ArrayList<>();
            {
                String[] list=list();
                if (list!=null)
                {
                    for (String i:list)
                    {
                        Resource r=addPath(i);
                        if (r.isDirectory())
                            deep.addAll(r.getAllResources());
                        else
                            deep.add(r);
                    }
                }
            }
            return deep;
        }
        catch(Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** Generate a properly encoded URL from a {@link File} instance.
     * @param file Target file. 
     * @return URL of the target file.
     * @throws MalformedURLException if unable to convert File to URL
     */
    public static URL toURL(File file) throws MalformedURLException
    {
        return file.toURI().toURL();
    }
}
