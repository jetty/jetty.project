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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;


/** 
 * ClassLoader for HttpContext.
 * <p>
 * Specializes URLClassLoader with some utility and file mapping
 * methods.
 * <p>
 * This loader defaults to the 2.3 servlet spec behavior where non
 * system classes are loaded from the classpath in preference to the
 * parent loader.  Java2 compliant loading, where the parent loader
 * always has priority, can be selected with the 
 * {@link org.eclipse.jetty.webapp.WebAppContext#setParentLoaderPriority(boolean)} 
 * method and influenced with {@link WebAppContext#isServerClass(String)} and 
 * {@link WebAppContext#isSystemClass(String)}.
 * <p>
 * If no parent class loader is provided, then the current thread 
 * context classloader will be used.  If that is null then the 
 * classloader that loaded this class is used as the parent.
 */
public class WebAppClassLoader extends URLClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    private static final Logger LOG = Log.getLogger(WebAppClassLoader.class);
    private static final ThreadLocal<Boolean> __loadServerClasses = new ThreadLocal<>();
    
    private final Context _context;
    private final ClassLoader _parent;
    private final Set<String> _extensions=new HashSet<String>();
    private String _name=String.valueOf(hashCode());
    private final List<ClassFileTransformer> _transformers = new CopyOnWriteArrayList<>();
    
    
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
        boolean isSystemClass(Class<?> clazz);

        /* ------------------------------------------------------------ */
        /** Is the class a Server Class.
         * A Server class is a class that is part of the implementation of 
         * the server and is NIT visible to a webapplication. The web
         * application may provide it's own implementation of the class,
         * to be loaded from WEB-INF/lib or WEB-INF/classes 
         * @param clazz The fully qualified name of the class.
         * @return True if the class is a server class.
         */
        boolean isServerClass(Class<?> clazz);

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

        boolean isServerResource(String name, URL parent_url);

        boolean isSystemResource(String name, URL webapp_url);
        
    }

    /* ------------------------------------------------------------ */
    /** Run an action with access to ServerClasses
     * <p>Run the passed {@link PrivilegedExceptionAction} with the classloader
     * configured so as to allow server classes to be visible</p>
     * @param <T> The type returned by the action
     * @param action The action to run
     * @return The return from the action
     * @throws Exception if thrown by the action
     */
    public static <T> T runWithServerClassAccess(PrivilegedExceptionAction<T> action) throws Exception
    {
        Boolean lsc=__loadServerClasses.get();
        try
        {
            __loadServerClasses.set(true);
            return action.run();
        }
        finally
        {
            if (lsc==null)
                __loadServerClasses.remove();
            else
                __loadServerClasses.set(lsc);
        }
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * Constructor.
     * @param context the context for this classloader
     * @throws IOException if unable to initialize from context
     */
    public WebAppClassLoader(Context context)
        throws IOException
    {
        this(null,context);
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * Constructor.
     * 
     * @param parent the parent classloader 
     * @param context the context for this classloader
     * @throws IOException if unable to initialize classloader
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
     * @throws IOException if unable to add classpath from resource
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
     * @throws IOException if unable to add classpath
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
                    URL url= resource.getURI().toURL();
                    addURL(url);
                }
                else if (resource.isDirectory())
                {
                    addURL(resource.getURI().toURL());
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Check file exists and is not nested jar: "+resource);
                    throw new IllegalArgumentException("File not resolvable or incompatible with URLClassloader: "+resource);
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
                    if(LOG.isDebugEnabled())
                        LOG.debug("addJar - {}", fn);
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
    @Override
    public PermissionCollection getPermissions(CodeSource cs)
    {
        PermissionCollection permissions=_context.getPermissions();
        PermissionCollection pc= (permissions == null) ? super.getPermissions(cs) : permissions;
        return pc;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        List<URL> from_parent = new ArrayList<>();
        List<URL> from_webapp = new ArrayList<>();
        
        Enumeration<URL> urls = _parent.getResources(name);
        while (urls!=null && urls.hasMoreElements())
        {
            URL url = urls.nextElement();
            if (Boolean.TRUE.equals(__loadServerClasses.get()) || !_context.isServerResource(name,url))
                from_parent.add(url);
        }

        urls = this.findResources(name);
        while (urls!=null && urls.hasMoreElements())
        {
            URL url = urls.nextElement();
            if (!_context.isSystemResource(name,url) || from_parent.isEmpty())
                from_webapp.add(url);
        }

        List<URL> resources;
        
        if (_context.isParentLoaderPriority())
        {
            from_parent.addAll(from_webapp);
            resources = from_parent;
        }
        else
        {
            from_webapp.addAll(from_parent);
            resources = from_webapp;
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug("getResources {} {}",name,resources);

        return Collections.enumeration(resources);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Get a resource from the classloader
     * 
     * NOTE: this method provides a convenience of hacking off a leading /
     * should one be present. This is non-standard and it is recommended 
     * to not rely on this behavior
     */
    @Override
    public URL getResource(String name)
    {
        URL resource=null;
        if (_context.isParentLoaderPriority())
        {
            URL parent_url=_parent.getResource(name);
            
            // return if we have a url the webapp is allowed to see
            if (parent_url!=null
                && (Boolean.TRUE.equals(__loadServerClasses.get()) 
                    || !_context.isServerResource(name,parent_url)))
                resource = parent_url;
            else
            {
                URL webapp_url = this.findResource(name);

                // If found here then OK to use regardless of system or server classes
                // If it is a system resource, we've already tried to load from parent, so
                // would have returned it.
                // If it is a server resource, doesn't matter as we have loaded it from the 
                // webapp
                if (webapp_url!=null)
                    resource = webapp_url;
            }
        }
        else
        {
            URL webapp_url = this.findResource(name);

            if (webapp_url!=null && !_context.isSystemResource(name,webapp_url))
                resource = webapp_url;
            else
            {

                // Couldn't find or see a webapp resource, so try a parent
                URL parent_url=_parent.getResource(name);
                if (parent_url!=null
                    && (Boolean.TRUE.equals(__loadServerClasses.get()) 
                        || !_context.isServerResource(name,parent_url)))
                    resource = parent_url;
                // We couldn't find a parent resource, so OK to return a webapp one if it exists 
                // and we just couldn't see it before 
                else if (webapp_url!=null)
                    resource = webapp_url;
            }
        }
        
        // Perhaps this failed due to leading /
        if (resource==null && name.startsWith("/"))
            resource = getResource(name.substring(1));

        if (LOG.isDebugEnabled())
            LOG.debug("getResource {} {}",name,resource);
       
        return resource;
        
    }

    /* ------------------------------------------------------------ */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (getClassLoadingLock(name))
        {            
            ClassNotFoundException ex= null;
            Class<?> parent_class = null;
            Class<?> webapp_class = null;
            
            // Has this loader loaded the class already?
            webapp_class = findLoadedClass(name);
            if (webapp_class != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("found webapp loaded {}",webapp_class);
                return webapp_class;
            }
            
            // Should we try the parent loader first?
            if (_context.isParentLoaderPriority())
            {
                // Try the parent loader
                try
                {
                    parent_class = _parent.loadClass(name); 

                    // If the webapp is allowed to see this class
                    if (Boolean.TRUE.equals(__loadServerClasses.get()) || !_context.isServerClass(parent_class))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("PLP parent loaded {}",parent_class);
                        return parent_class;
                    }
                }
                catch (ClassNotFoundException e)
                {
                    // Save it for later
                    ex = e;
                }
                
                // Try the webapp loader
                try
                {
                    // If found here then OK to use regardless of system or server classes
                    // If it is a system class, we've already tried to load from parent, so
                    // would have returned it.
                    // If it is a server class, doesn't matter as we have loaded it from the 
                    // webapp
                    webapp_class = this.findClass(name);
                    resolveClass(webapp_class);
                    if (LOG.isDebugEnabled())
                        LOG.debug("PLP webapp loaded {}",webapp_class);
                    return webapp_class;
                }
                catch (ClassNotFoundException e)
                {
                    if (ex==null)
                        ex = e;
                    else
                        ex.addSuppressed(e);
                }
                
                throw ex;
            }
            else
            {
                // Not parent loader priority, so...

                // Try the webapp classloader first
                // Look in the webapp classloader as a resource, to avoid 
                // loading a system class.
                String path = name.replace('.', '/').concat(".class");
                URL webapp_url = findResource(path);
                
                if (webapp_url!=null && !_context.isSystemResource(name,webapp_url))
                {
                    webapp_class = this.foundClass(name,webapp_url);
                    resolveClass(webapp_class);
                    if (LOG.isDebugEnabled())
                        LOG.debug("WAP webapp loaded {}",webapp_class);
                    return webapp_class;
                }

                // Try the parent loader
                try
                {
                    parent_class = _parent.loadClass(name); 
                    
                    // If the webapp is allowed to see this class
                    if (Boolean.TRUE.equals(__loadServerClasses.get()) || !_context.isServerClass(parent_class))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("WAP parent loaded {}",parent_class);
                        return parent_class;
                    }
                }
                catch (ClassNotFoundException e)
                {
                    ex=e;
                }

                // We couldn't find a parent class, so OK to return a webapp one if it exists 
                // and we just couldn't see it before 
                if (webapp_url!=null)
                {
                    webapp_class = this.foundClass(name,webapp_url);
                    resolveClass(webapp_class);
                    if (LOG.isDebugEnabled())
                        LOG.debug("WAP !server webapp loaded {}",webapp_class);
                    return webapp_class;
                }
                
                throw ex==null?new ClassNotFoundException(name):ex;
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param transformer the transformer to add
     * @deprecated {@link #addTransformer(ClassFileTransformer)} instead
     */
    @Deprecated
    public void addClassFileTransformer(ClassFileTransformer transformer)
    {
        _transformers.add(transformer);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param transformer the transformer to remove
     * @return true if transformer was removed
     * @deprecated use {@link #removeTransformer(ClassFileTransformer)} instead
     */
    @Deprecated
    public boolean removeClassFileTransformer(ClassFileTransformer transformer)
    {
        return _transformers.remove(transformer);
    }

    /* ------------------------------------------------------------ */
    public void addTransformer(ClassFileTransformer transformer)
    {
        _transformers.add(transformer);
    }
    
    /* ------------------------------------------------------------ */
    public boolean removeTransformer(ClassFileTransformer transformer)
    {
        return _transformers.remove(transformer);
    }

    /* ------------------------------------------------------------ */
    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException
    {
        if (_transformers.isEmpty())
            return super.findClass(name);

        String path = name.replace('.', '/').concat(".class");
        URL url = findResource(path);
        if (url==null)
            throw new ClassNotFoundException(name);
        return foundClass(name,url);
    }
    
    /* ------------------------------------------------------------ */
    protected Class<?> foundClass(final String name, URL url) throws ClassNotFoundException
    {
        if (_transformers.isEmpty())
            return super.findClass(name);

        InputStream content=null;
        try
        {
            content = url.openStream();
            byte[] bytes = IO.readBytes(content);

            if (LOG.isDebugEnabled())
                LOG.debug("foundClass({}) url={} cl={}",name,url,this);

            for (ClassFileTransformer transformer : _transformers)
            {
                byte[] tmp = transformer.transform(this,name,null,null,bytes);
                if (tmp != null)
                    bytes = tmp;
            }

            return defineClass(name,bytes,0,bytes.length);
        }
        catch (IOException e)
        {
            throw new ClassNotFoundException(name,e);
        }
        catch (IllegalClassFormatException e)
        {
            throw new ClassNotFoundException(name,e);
        }
        finally
        {
            if (content!=null)
            {
                try
                {
                    content.close(); 
                }
                catch (IOException e)
                {
                    throw new ClassNotFoundException(name,e);
                }
            }
        }
    }

    
    /* ------------------------------------------------------------ */
    @Override
    public void close() throws IOException
    {
        super.close();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return "WebAppClassLoader=" + _name+"@"+Long.toHexString(hashCode());
    }
    
}
