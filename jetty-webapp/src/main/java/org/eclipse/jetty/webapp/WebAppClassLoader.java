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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;


/* ------------------------------------------------------------ */
/** ClassLoader for HttpContext.
 * Specializes URLClassLoader with some utility and file mapping
 * methods.
 *
 * This loader defaults to the 2.3 servlet spec behavior where non
 * system classes are loaded from the classpath in preference to the
 * parent loader.  Java2 compliant loading, where the parent loader
 * always has priority, can be selected with the 
 * {@link org.eclipse.jetty.webapp.WebAppContext#setParentLoaderPriority(boolean)} 
 * method and influenced with {@link WebAppContext#isServerClass(String)} and 
 * {@link WebAppContext#isSystemClass(String)}.
 *
 * If no parent class loader is provided, then the current thread 
 * context classloader will be used.  If that is null then the 
 * classloader that loaded this class is used as the parent.
 * 
 */
public class WebAppClassLoader extends URLClassLoader
{
    private static final Logger LOG = Log.getLogger(WebAppClassLoader.class);

    private final Context _context;
    private final ClassLoader _parent;
    private final Set<String> _extensions=new HashSet<String>();
    private String _name=String.valueOf(hashCode());
    
    /* ------------------------------------------------------------ */
    /** The Context in which the classloader operates.
     */
    public interface Context
    {
        /* ------------------------------------------------------------ */
        /** Convert a URL or path to a Resource.
         * The default implementation
         * is a wrapper for {@link Resource#newResource(String)}.
         * @param urlOrPath The URL or path to convert
         * @return The Resource for the URL/path
         * @throws IOException The Resource could not be created.
         */
        Resource newResource(String urlOrPath) throws IOException;

        /* ------------------------------------------------------------ */
        /**
         * @return Returns the permissions.
         */
        PermissionCollection getPermissions();

        /* ------------------------------------------------------------ */
        /** Is the class a System Class.
         * A System class is a class that is visible to a webapplication,
         * but that cannot be overridden by the contents of WEB-INF/lib or
         * WEB-INF/classes 
         * @param clazz The fully qualified name of the class.
         * @return True if the class is a system class.
         */
        boolean isSystemClass(String clazz);

        /* ------------------------------------------------------------ */
        /** Is the class a Server Class.
         * A Server class is a class that is part of the implementation of 
         * the server and is NIT visible to a webapplication. The web
         * application may provide it's own implementation of the class,
         * to be loaded from WEB-INF/lib or WEB-INF/classes 
         * @param clazz The fully qualified name of the class.
         * @return True if the class is a server class.
         */
        boolean isServerClass(String clazz);

        /* ------------------------------------------------------------ */
        /**
         * @return True if the classloader should delegate first to the parent 
         * classloader (standard java behaviour) or false if the classloader 
         * should first try to load from WEB-INF/lib or WEB-INF/classes (servlet 
         * spec recommendation).
         */
        boolean isParentLoaderPriority();
        
        /* ------------------------------------------------------------ */
        String getExtraClasspath();
        
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public WebAppClassLoader(Context context)
        throws IOException
    {
        this(null,context);
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public WebAppClassLoader(ClassLoader parent, Context context)
        throws IOException
    {
        super(new URL[]{},parent!=null?parent
                :(Thread.currentThread().getContextClassLoader()!=null?Thread.currentThread().getContextClassLoader()
                        :(WebAppClassLoader.class.getClassLoader()!=null?WebAppClassLoader.class.getClassLoader()
                                :ClassLoader.getSystemClassLoader())));
        _parent=getParent();
        _context=context;
        if (_parent==null)
            throw new IllegalArgumentException("no parent classloader!");
        
        _extensions.add(".jar");
        _extensions.add(".zip");
        
        // TODO remove this system property
        String extensions = System.getProperty(WebAppClassLoader.class.getName() + ".extensions");
        if(extensions!=null)
        {
            StringTokenizer tokenizer = new StringTokenizer(extensions, ",;");
            while(tokenizer.hasMoreTokens())
                _extensions.add(tokenizer.nextToken().trim());
        }
        
        if (context.getExtraClasspath()!=null)
            addClassPath(context.getExtraClasspath());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the name of the classloader
     */
    public String getName()
    {
        return _name;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name the name of the classloader
     */
    public void setName(String name)
    {
        _name=name;
    }
    

    /* ------------------------------------------------------------ */
    public Context getContext()
    {
        return _context;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param resource Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     */
    public void addClassPath(Resource resource)
        throws IOException
    {
        if (resource instanceof ResourceCollection)
        {
            for (Resource r : ((ResourceCollection)resource).getResources())
                addClassPath(r);
        }
        else
        {
            addClassPath(resource.toString());
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param classPath Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     */
    public void addClassPath(String classPath)
    	throws IOException
    {
        if (classPath == null)
            return;
            
        StringTokenizer tokenizer= new StringTokenizer(classPath, ",;");
        while (tokenizer.hasMoreTokens())
        {
            Resource resource= _context.newResource(tokenizer.nextToken().trim());
            if (LOG.isDebugEnabled())
                LOG.debug("Path resource=" + resource);

            // Add the resource
            if (resource.isDirectory() && resource instanceof ResourceCollection)
                addClassPath(resource);
            else
            {
                // Resolve file path if possible
                File file= resource.getFile();
                if (file != null)
                {
                    URL url= resource.getURL();
                    addURL(url);
                }
                else if (resource.isDirectory())
                    addURL(resource.getURL());
                else
                    throw new IllegalArgumentException("!file: "+resource);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param file Checks if this file type can be added to the classpath.
     */
    private boolean isFileSupported(String file)
    {
        int dot = file.lastIndexOf('.');
        return dot!=-1 && _extensions.contains(file.substring(dot));
    }
    
    /* ------------------------------------------------------------ */
    /** Add elements to the class path for the context from the jar and zip files found
     *  in the specified resource.
     * @param lib the resource that contains the jar and/or zip files.
     */
    public void addJars(Resource lib)
    {
        if (lib.exists() && lib.isDirectory())
        {
            String[] files=lib.list();
            for (int f=0;files!=null && f<files.length;f++)
            {
                try 
                {
                    Resource fn=lib.addPath(files[f]);
                    String fnlc=fn.getName().toLowerCase(Locale.ENGLISH);
                    // don't check if this is a directory, see Bug 353165
                    if (isFileSupported(fnlc))
                    {
                        String jar=fn.toString();
                        jar=StringUtil.replace(jar, ",", "%2C");
                        jar=StringUtil.replace(jar, ";", "%3B");
                        addClassPath(jar);
                    }
                }
                catch (Exception ex)
                {
                    LOG.warn(Log.EXCEPTION,ex);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public PermissionCollection getPermissions(CodeSource cs)
    {
        // TODO check CodeSource
        PermissionCollection permissions=_context.getPermissions();
        PermissionCollection pc= (permissions == null) ? super.getPermissions(cs) : permissions;
        return pc;
    }

    /* ------------------------------------------------------------ */
    public Enumeration<URL> getResources(String name) throws IOException
    {
        boolean system_class=_context.isSystemClass(name);
        boolean server_class=_context.isServerClass(name);
        
        List<URL> from_parent = toList(server_class?null:_parent.getResources(name));
        List<URL> from_webapp = toList((system_class&&!from_parent.isEmpty())?null:this.findResources(name));
            
        if (_context.isParentLoaderPriority())
        {
            from_parent.addAll(from_webapp);
            return Collections.enumeration(from_parent);
        }
        from_webapp.addAll(from_parent);
        return Collections.enumeration(from_webapp);
    }

    /* ------------------------------------------------------------ */
    private List<URL> toList(Enumeration<URL> e)
    {
        if (e==null)
            return new ArrayList<URL>();
        return Collections.list(e);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get a resource from the classloader
     * 
     * NOTE: this method provides a convenience of hacking off a leading /
     * should one be present. This is non-standard and it is recommended 
     * to not rely on this behavior
     */
    public URL getResource(String name)
    {
        URL url= null;
        boolean tried_parent= false;
        boolean system_class=_context.isSystemClass(name);
        boolean server_class=_context.isServerClass(name);
        
        if (system_class && server_class)
            return null;
        
        if (_parent!=null &&(_context.isParentLoaderPriority() || system_class ) && !server_class)
        {
            tried_parent= true;
            
            if (_parent!=null)
                url= _parent.getResource(name);
        }

        if (url == null)
        {
            url= this.findResource(name);

            if (url == null && name.startsWith("/"))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("HACK leading / off " + name);
                url= this.findResource(name.substring(1));
            }
        }

        if (url == null && !tried_parent && !server_class )
        {
            if (_parent!=null)
                url= _parent.getResource(name);
        }

        if (url != null)
            if (LOG.isDebugEnabled())
                LOG.debug("getResource("+name+")=" + url);

        return url;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        return loadClass(name, false);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        Class<?> c= findLoadedClass(name);
        ClassNotFoundException ex= null;
        boolean tried_parent= false;
        
        boolean system_class=_context.isSystemClass(name);
        boolean server_class=_context.isServerClass(name);
        
        if (system_class && server_class)
        {
            return null;
        }
        
        if (c == null && _parent!=null && (_context.isParentLoaderPriority() || system_class) && !server_class)
        {
            tried_parent= true;
            try
            {
                c= _parent.loadClass(name);
                if (LOG.isDebugEnabled())
                    LOG.debug("loaded " + c);
            }
            catch (ClassNotFoundException e)
            {
                ex= e;
            }
        }

        if (c == null)
        {
            try
            {
                c= this.findClass(name);
            }
            catch (ClassNotFoundException e)
            {
                ex= e;
            }
        }

        if (c == null && _parent!=null && !tried_parent && !server_class )
            c= _parent.loadClass(name);

        if (c == null)
            throw ex;

        if (resolve)
            resolveClass(c);

        if (LOG.isDebugEnabled())
            LOG.debug("loaded " + c+ " from "+c.getClassLoader());
        
        return c;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return "WebAppClassLoader=" + _name+"@"+Long.toHexString(hashCode());
    }
}
