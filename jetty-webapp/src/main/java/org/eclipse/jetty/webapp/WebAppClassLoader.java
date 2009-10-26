// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.HashSet;
import java.util.StringTokenizer;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;


/* ------------------------------------------------------------ */
/** ClassLoader for HttpContext.
 * Specializes URLClassLoader with some utility and file mapping
 * methods.
 *
 * This loader defaults to the 2.3 servlet spec behaviour where non
 * system classes are loaded from the classpath in preference to the
 * parent loader.  Java2 compliant loading, where the parent loader
 * always has priority, can be selected with the 
 * {@link org.eclipse.jetty.server.server.webapp.WebAppContext#setParentLoaderPriority(boolean)} 
 * method and influenced with {@link WebAppContext#isServerClass(String)} and 
 * {@link WebAppContext#isSystemClass(String)}.
 *
 * If no parent class loader is provided, then the current thread 
 * context classloader will be used.  If that is null then the 
 * classloader that loaded this class is used as the parent.
 * 
 * 
 */
public class WebAppClassLoader extends URLClassLoader 
{
    private String _name;
    private WebAppContext _context;
    private ClassLoader _parent;
    private HashSet<String> _extensions;
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public WebAppClassLoader(WebAppContext context)
        throws IOException
    {
        this(null,context);
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public WebAppClassLoader(ClassLoader parent, WebAppContext context)
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
        
        _extensions = new HashSet<String>();
        _extensions.add(".jar");
        _extensions.add(".zip");
        
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
    public ContextHandler getContext()
    {
        return _context;
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
            Resource resource= _context.newResource(tokenizer.nextToken());
            if (Log.isDebugEnabled())
                Log.debug("Path resource=" + resource);

            // Resolve file path if possible
            File file= resource.getFile();
            if (file != null)
            {
                URL url= resource.getURL();
                addURL(url);
            }
            else
            {
                // Add resource or expand jar/
                if (!resource.isDirectory() && file == null)
                {
                    throw new IllegalArgumentException("!file: "+resource);
                }
                else
                {
                    URL url= resource.getURL();
                    addURL(url);
                }
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
     * @param append true if the classpath entries are to be appended to any
     * existing classpath, or false if they replace the existing classpath.
     * @see #setClassPath(String)
     */
    public void addJars(Resource lib)
    {
        if (lib.exists() && lib.isDirectory())
        {
            String[] files=lib.list();
            for (int f=0;files!=null && f<files.length;f++)
            {
                try {
                    Resource fn=lib.addPath(files[f]);
                    String fnlc=fn.getName().toLowerCase();
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
                    Log.warn(Log.EXCEPTION,ex);
                }
            }
        }
    }
    /* ------------------------------------------------------------ */
    public void destroy()
    {
        this._parent=null;
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
    public URL getResource(String name)
    {
        URL url= null;
        boolean tried_parent= false;
        if (_context.isParentLoaderPriority() || _context.isSystemClass(name))
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
                if (Log.isDebugEnabled())
                    Log.debug("HACK leading / off " + name);
                url= this.findResource(name.substring(1));
            }
        }

        if (url == null && !tried_parent && !_context.isServerClass(name) )
        {
            if (_parent!=null)
                url= _parent.getResource(name);
        }

        if (url != null)
            if (Log.isDebugEnabled())
                Log.debug("getResource("+name+")=" + url);

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
        
        if (c == null && _parent!=null && (_context.isParentLoaderPriority() || system_class) )
        {
            tried_parent= true;
            try
            {
                c= _parent.loadClass(name);
                if (Log.isDebugEnabled())
                    Log.debug("loaded " + c);
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

        if (Log.isDebugEnabled())
            Log.debug("loaded " + c+ " from "+c.getClassLoader());
        
        return c;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        if (Log.isDebugEnabled())
            return "ContextLoader@" + _name + "(" + LazyList.array2List(getURLs()) + ") / " + _parent;
        return "ContextLoader@" + _name;
    }
    
}
