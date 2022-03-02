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

package org.eclipse.jetty.ee9.osgi.boot.internal.webapp;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee9.osgi.boot.utils.BundleClassLoaderHelperFactory;
import org.eclipse.jetty.ee9.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGiWebappClassLoader
 *
 *
 * Extends the webapp classloader to also use the classloader of the Bundle defining the webapp.
 */
public class OSGiWebappClassLoader extends WebAppClassLoader implements BundleReference
{

    private static final Logger LOG = LoggerFactory.getLogger(OSGiWebappClassLoader.class.getName());

    /**
     * when a logging framework is setup in the osgi classloaders, it can access
     * this and register the classes that must not be found in the jar.
     */
    public static final Set<String> JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED = new HashSet<>();

    public static void addClassThatIdentifiesAJarThatMustBeRejected(Class<?> zclass)
    {
        JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED.add(TypeUtil.toClassReference(zclass.getName()));
    }

    public static void addClassThatIdentifiesAJarThatMustBeRejected(String zclassName)
    {
        JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED.add(TypeUtil.toClassReference(zclassName));
    }

    static
    {
        addClassThatIdentifiesAJarThatMustBeRejected(HttpServlet.class);
    }

    private ClassLoader _osgiBundleClassLoader;

    private Bundle _contributor;

    /**
     * @param parent The parent classloader.
     * @param context The WebAppContext
     * @param contributor The bundle that defines this web-application.
     * @throws IOException if unable to cerate the OSGiWebappClassLoader
     */
    public OSGiWebappClassLoader(ClassLoader parent, WebAppContext context, Bundle contributor)
        throws IOException
    {
        super(parent, context);
        _contributor = contributor;
        _osgiBundleClassLoader = BundleClassLoaderHelperFactory.getFactory().getHelper().getBundleClassLoader(contributor);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        try
        {
            return _osgiBundleClassLoader.loadClass(name);
        }
        catch (ClassNotFoundException cne)
        {
            try
            {

                return super.findClass(name);
            }
            catch (ClassNotFoundException cne2)
            {
                throw cne;
            }
        }
    }

    /**
     * Returns the <code>Bundle</code> that defined this web-application.
     *
     * @return The <code>Bundle</code> object associated with this
     * <code>BundleReference</code>.
     */
    @Override
    public Bundle getBundle()
    {
        return _contributor;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        Enumeration<URL> osgiUrls = _osgiBundleClassLoader.getResources(name);
        if (osgiUrls != null && osgiUrls.hasMoreElements())
            return osgiUrls;
        
        Enumeration<URL> urls = super.getResources(name);
        return urls;
    }

    @Override
    public URL getResource(String name)
    {
        URL url = _osgiBundleClassLoader.getResource(name);
        return url != null ? url : super.getResource(name);
    }

    @Override
    public URL findResource(String name)
    {
        URL url = _osgiBundleClassLoader.getResource(name);
        return url != null ? url : super.findResource(name);
    }

    /**
     * Try to load the class from the bundle classloader.
     * We do NOT load it as a resource as the WebAppClassLoader does because the
     * url that is returned is an osgi-special url that does not play
     * properly with WebAppClassLoader's method of extracting the class
     * from the resource.  This implementation directly asks the osgi
     * bundle classloader to load the given class name.
     *
     * @see org.eclipse.jetty.ee9.webapp.WebAppClassLoader#loadAsResource(java.lang.String, boolean)
     */
    @Override
    protected Class<?> loadAsResource(String name, boolean checkSystemResource) throws ClassNotFoundException
    {
        try
        {
            return _osgiBundleClassLoader.loadClass(name);
        }
        catch (ClassNotFoundException cne)
        {
            try
            {
                return super.loadAsResource(name, checkSystemResource);
            }
            catch (ClassNotFoundException cne2)
            {
                throw cne;
            }
        }
    }

    private List<URL> toList(Enumeration<URL> e, Enumeration<URL> e2)
    {
        List<URL> list = new ArrayList<>();
        while (e != null && e.hasMoreElements())
        {
            list.add(e.nextElement());
        }
        while (e2 != null && e2.hasMoreElements())
        {
            list.add(e2.nextElement());
        }
        return list;
    }

    /**
     * Parse the classpath ourselves to be able to filter things. This is a
     * derivative work of the super class
     */
    @Override
    public void addClassPath(String classPath) throws IOException
    {
        for (Resource resource : Resource.fromList(classPath, false, (path) -> getContext().newResource(path)))
        {
            File file = resource.getFile();
            if (file != null && isAcceptableLibrary(file, JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED))
            {
                super.addClassPath(resource);
            }
            else
            {
                LOG.info("Did not add {} to the classloader of the webapp {}", resource, getContext());
            }
        }
    }

    /**
     * @return true if the lib should be included in the webapp classloader.
     */
    private boolean isAcceptableLibrary(File file, Set<String> pathToClassFiles)
    {
        try
        {
            if (file.isDirectory())
            {
                for (String criteria : pathToClassFiles)
                {
                    if (new File(file, criteria).exists())
                    {
                        return false;
                    }
                }
            }
            else
            {
                JarFile jar = null;
                try
                {
                    jar = new JarFile(file);
                    for (String criteria : pathToClassFiles)
                    {
                        if (jar.getEntry(criteria) != null)
                        {
                            return false;
                        }
                    }
                }
                finally
                {
                    if (jar != null)
                        try
                        {
                            jar.close();
                        }
                        catch (IOException ignored)
                        {
                        }
                }
            }
        }
        catch (IOException e)
        {
            // nevermind. just trying our best
            LOG.trace("IGNORED", e);
        }
        return true;
    }
}
