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

package org.eclipse.jetty.ee10.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.util.ClassVisibilityChecker;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * {@link WebAppContext#setParentLoaderPriority(boolean)}
 * method and influenced with {@link WebAppContext#isServerClass(Class)} and
 * {@link WebAppContext#isSystemClass(Class)}.
 * <p>
 * If no parent class loader is provided, then the current thread
 * context classloader will be used.  If that is null then the
 * classloader that loaded this class is used as the parent.
 */
public class WebAppClassLoader extends URLClassLoader implements ClassVisibilityChecker
{
    static
    {
        registerAsParallelCapable();
    }

    private static final Logger LOG = LoggerFactory.getLogger(WebAppClassLoader.class);
    private static final ThreadLocal<Boolean> __loadServerClasses = new ThreadLocal<>();

    private final Context _context;
    private final ClassLoader _parent;
    private final Set<String> _extensions = new HashSet<String>();
    private String _name = String.valueOf(hashCode());
    private final List<ClassFileTransformer> _transformers = new CopyOnWriteArrayList<>();

    /**
     * The Context in which the classloader operates.
     */
    public interface Context extends ClassVisibilityChecker
    {
        /**
         * Convert a URL or path to a Resource.
         * The default implementation
         * is a wrapper for {@link Resource#newResource(String)}.
         *
         * @param urlOrPath The URL or path to convert
         * @return The Resource for the URL/path
         * @throws IOException The Resource could not be created.
         */
        Resource newResource(String urlOrPath) throws IOException;

        /**
         * @return Returns the permissions.
         */
        PermissionCollection getPermissions();

        /**
         * @return True if the classloader should delegate first to the parent
         * classloader (standard java behaviour) or false if the classloader
         * should first try to load from WEB-INF/lib or WEB-INF/classes (servlet
         * spec recommendation).
         */
        boolean isParentLoaderPriority();

        List<Resource> getExtraClasspath();

        boolean isServerResource(String name, URL parentUrl);

        boolean isSystemResource(String name, URL webappUrl);
    }

    /**
     * Run an action with access to ServerClasses
     * <p>Run the passed {@link PrivilegedExceptionAction} with the classloader
     * configured so as to allow server classes to be visible</p>
     *
     * @param <T> The type returned by the action
     * @param action The action to run
     * @param <T> the type of PrivilegedExceptionAction
     * @return The return from the action
     * @throws Exception if thrown by the action
     */
    public static <T> T runWithServerClassAccess(PrivilegedExceptionAction<T> action) throws Exception
    {
        Boolean lsc = __loadServerClasses.get();
        try
        {
            __loadServerClasses.set(true);
            return action.run();
        }
        finally
        {
            if (lsc == null)
                __loadServerClasses.remove();
            else
                __loadServerClasses.set(lsc);
        }
    }

    /**
     * Constructor.
     *
     * @param context the context for this classloader
     * @throws IOException if unable to initialize from context
     */
    public WebAppClassLoader(Context context)
        throws IOException
    {
        this(null, context);
    }

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
        super(new URL[]{}, parent != null ? parent
            : (Thread.currentThread().getContextClassLoader() != null ? Thread.currentThread().getContextClassLoader()
            : (WebAppClassLoader.class.getClassLoader() != null ? WebAppClassLoader.class.getClassLoader()
            : ClassLoader.getSystemClassLoader())));
        _parent = getParent();
        _context = context;
        if (_parent == null)
            throw new IllegalArgumentException("no parent classloader!");

        _extensions.add(".jar");
        _extensions.add(".zip");

        // TODO remove this system property
        String extensions = System.getProperty(WebAppClassLoader.class.getName() + ".extensions");
        if (extensions != null)
        {
            StringTokenizer tokenizer = new StringTokenizer(extensions, StringUtil.DEFAULT_DELIMS);
            while (tokenizer.hasMoreTokens())
            {
                _extensions.add(tokenizer.nextToken().trim());
            }
        }

        if (context.getExtraClasspath() != null)
        {
            for (Resource resource : context.getExtraClasspath())
            {
                addClassPath(resource);
            }
        }
    }

    /**
     * @return the name of the classloader
     */
    public String getName()
    {
        return _name;
    }

    /**
     * @param name the name of the classloader
     */
    public void setName(String name)
    {
        _name = name;
    }

    public Context getContext()
    {
        return _context;
    }

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
            {
                addClassPath(r);
            }
        }
        else
        {
            // Resolve file path if possible
            Path file = resource.getPath();
            if (file != null)
            {
                URL url = resource.getURI().toURL();
                addURL(url);
            }
            else if (resource.isDirectory())
            {
                addURL(resource.getURI().toURL());
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Check file exists and is not nested jar: {}", resource);
                throw new IllegalArgumentException("File not resolvable or incompatible with URLClassloader: " + resource);
            }
        }
    }

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

        for (Resource resource : Resource.fromList(classPath, false, _context::newResource))
        {
            addClassPath(resource);
        }
    }

    /**
     * @param file Checks if this file type can be added to the classpath.
     */
    private boolean isFileSupported(String file)
    {
        int dot = file.lastIndexOf('.');
        return dot != -1 && _extensions.contains(file.substring(dot));
    }

    /**
     * Add elements to the class path for the context from the jar and zip files found
     * in the specified resource.
     *
     * @param lib the resource that contains the jar and/or zip files.
     */
    public void addJars(Resource lib)
    {
        if (lib.exists() && lib.isDirectory())
        {
            String[] entries = lib.list();
            if (entries != null)
            {
                Arrays.sort(entries);

                for (String entry : entries)
                {
                    try
                    {
                        Resource resource = lib.addPath(entry);
                        if (LOG.isDebugEnabled())
                            LOG.debug("addJar - {}", resource);
                        String fnlc = resource.getName().toLowerCase(Locale.ENGLISH);
                        // don't check if this is a directory (prevents use of symlinks), see Bug 353165
                        if (isFileSupported(fnlc))
                        {
                            String jar = URIUtil.encodeSpecific(resource.toString(), ",;");
                            addClassPath(jar);
                        }
                    }
                    catch (Exception ex)
                    {
                        LOG.warn("Unable to load WEB-INF/lib JAR {}", entry, ex);
                    }
                }
            }
        }
    }

    @Override
    public PermissionCollection getPermissions(CodeSource cs)
    {
        PermissionCollection permissions = _context.getPermissions();
        PermissionCollection pc = (permissions == null) ? super.getPermissions(cs) : permissions;
        return pc;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        List<URL> fromParent = new ArrayList<>();
        List<URL> fromWebapp = new ArrayList<>();

        Enumeration<URL> urls = _parent.getResources(name);
        while (urls != null && urls.hasMoreElements())
        {
            URL url = urls.nextElement();
            if (Boolean.TRUE.equals(__loadServerClasses.get()) || !_context.isServerResource(name, url))
                fromParent.add(url);
        }

        urls = this.findResources(name);
        while (urls != null && urls.hasMoreElements())
        {
            URL url = urls.nextElement();
            if (!_context.isSystemResource(name, url) || fromParent.isEmpty())
                fromWebapp.add(url);
        }

        List<URL> resources;

        if (_context.isParentLoaderPriority())
        {
            fromParent.addAll(fromWebapp);
            resources = fromParent;
        }
        else
        {
            fromWebapp.addAll(fromParent);
            resources = fromWebapp;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("getResources {} {}", name, resources);

        return Collections.enumeration(resources);
    }

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
        URL resource = null;
        if (_context.isParentLoaderPriority())
        {
            URL parentUrl = _parent.getResource(name);

            // return if we have a url the webapp is allowed to see
            if (parentUrl != null &&
                (Boolean.TRUE.equals(__loadServerClasses.get()) ||
                    !_context.isServerResource(name, parentUrl)))
                resource = parentUrl;
            else
            {
                URL webappUrl = this.findResource(name);

                // If found here then OK to use regardless of system or server classes
                // If it is a system resource, we've already tried to load from parent, so
                // would have returned it.
                // If it is a server resource, doesn't matter as we have loaded it from the 
                // webapp
                if (webappUrl != null)
                    resource = webappUrl;
            }
        }
        else
        {
            URL webappUrl = this.findResource(name);

            if (webappUrl != null && !_context.isSystemResource(name, webappUrl))
                resource = webappUrl;
            else
            {

                // Couldn't find or see a webapp resource, so try a parent
                URL parentUrl = _parent.getResource(name);
                if (parentUrl != null &&
                    (Boolean.TRUE.equals(__loadServerClasses.get()) ||
                        !_context.isServerResource(name, parentUrl)))
                    resource = parentUrl;
                    // We couldn't find a parent resource, so OK to return a webapp one if it exists
                    // and we just couldn't see it before
                else if (webappUrl != null)
                    resource = webappUrl;
            }
        }

        // Perhaps this failed due to leading /
        if (resource == null && name.startsWith("/"))
            resource = getResource(name.substring(1));

        if (LOG.isDebugEnabled())
            LOG.debug("getResource {} {}", name, resource);

        return resource;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (getClassLoadingLock(name))
        {
            ClassNotFoundException ex = null;
            Class<?> parentClass = null;
            Class<?> webappClass = null;

            // Has this loader loaded the class already?
            webappClass = findLoadedClass(name);
            if (webappClass != null)
            {
                return webappClass;
            }

            // Should we try the parent loader first?
            if (_context.isParentLoaderPriority())
            {
                // Try the parent loader
                try
                {
                    parentClass = _parent.loadClass(name);
                    if (parentClass == null)
                        throw new ClassNotFoundException("Bad ClassLoader: returned null for loadClass(" + name + ")");

                    // If the webapp is allowed to see this class
                    if (Boolean.TRUE.equals(__loadServerClasses.get()) || !_context.isServerClass(parentClass))
                    {
                        return parentClass;
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
                    webappClass = this.findClass(name);
                    if (resolve)
                        resolveClass(webappClass);
                    return webappClass;
                }
                catch (ClassNotFoundException e)
                {
                    if (ex == null)
                        ex = e;
                    else if (e != ex)
                        ex.addSuppressed(e);
                }

                throw ex;
            }
            else
            {
                // Not parent loader priority, so...
                webappClass = loadAsResource(name, true);
                if (webappClass != null)
                {
                    return webappClass;
                }

                // Try the parent loader
                try
                {
                    parentClass = _parent.loadClass(name);
                    // If the webapp is allowed to see this class
                    if (Boolean.TRUE.equals(__loadServerClasses.get()) || !_context.isServerClass(parentClass))
                    {
                        return parentClass;
                    }
                }
                catch (ClassNotFoundException e)
                {
                    ex = e;
                }

                // We couldn't find a parent class, so OK to return a webapp one if it exists 
                // and we just couldn't see it before 
                webappClass = loadAsResource(name, false);
                if (webappClass != null)
                {
                    return webappClass;
                }

                throw ex == null ? new ClassNotFoundException(name) : ex;
            }
        }
    }

    public void addTransformer(ClassFileTransformer transformer)
    {
        _transformers.add(transformer);
    }

    public boolean removeTransformer(ClassFileTransformer transformer)
    {
        return _transformers.remove(transformer);
    }

    /**
     * Look for the classname as a resource to avoid loading a class that is
     * potentially a system resource.
     *
     * @param name the name of the class to load
     * @param checkSystemResource if true and the class isn't a system class we return it
     * @return the loaded class
     * @throws ClassNotFoundException if the class cannot be found
     */
    protected Class<?> loadAsResource(final String name, boolean checkSystemResource) throws ClassNotFoundException
    {
        // Try the webapp classloader first
        // Look in the webapp classloader as a resource, to avoid 
        // loading a system class.
        Class<?> webappClass = null;
        String path = TypeUtil.toClassReference(name);
        URL webappUrl = findResource(path);

        if (webappUrl != null && (!checkSystemResource || !_context.isSystemResource(name, webappUrl)))
        {

            webappClass = this.foundClass(name, webappUrl);
            resolveClass(webappClass);
            if (LOG.isDebugEnabled())
                LOG.debug("WAP webapp loaded {}", webappClass);
        }

        return webappClass;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException
    {
        if (_transformers.isEmpty())
        {
            return super.findClass(name);
        }

        String path = TypeUtil.toClassReference(name);
        URL url = findResource(path);
        if (url == null)
            throw new ClassNotFoundException(name);
        return foundClass(name, url);
    }

    protected Class<?> foundClass(final String name, URL url) throws ClassNotFoundException
    {
        if (_transformers.isEmpty())
            return super.findClass(name);

        InputStream content = null;
        try
        {
            content = url.openStream();
            byte[] bytes = IO.readBytes(content);

            for (ClassFileTransformer transformer : _transformers)
            {
                byte[] tmp = transformer.transform(this, name, null, null, bytes);
                if (tmp != null)
                    bytes = tmp;
            }

            return defineClass(name, bytes, 0, bytes.length);
        }
        catch (IOException e)
        {
            throw new ClassNotFoundException(name, e);
        }
        catch (IllegalClassFormatException e)
        {
            throw new ClassNotFoundException(name, e);
        }
        finally
        {
            if (content != null)
            {
                try
                {
                    content.close();
                }
                catch (IOException e)
                {
                    throw new ClassNotFoundException(name, e);
                }
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        super.close();
    }

    @Override
    public String toString()
    {
        return String.format("%s{%s}@%x", this.getClass().getSimpleName(), _name, hashCode());
    }

    @Override
    public boolean isSystemClass(Class<?> clazz)
    {
        return _context.isSystemClass(clazz);
    }

    @Override
    public boolean isServerClass(Class<?> clazz)
    {
        return _context.isServerClass(clazz);
    }
}
