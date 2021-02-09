//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.JarFile;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelperFactory;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;

/**
 * OSGiWebappClassLoader
 *
 *
 * Extends the webapp classloader to also use the classloader of the Bundle defining the webapp.
 */
public class OSGiWebappClassLoader extends WebAppClassLoader implements BundleReference
{

    private static final Logger __logger = Log.getLogger(OSGiWebappClassLoader.class.getName());

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
     * @see org.eclipse.jetty.webapp.WebAppClassLoader#loadAsResource(java.lang.String, boolean)
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

        StringTokenizer tokenizer = new StringTokenizer(classPath, ",;");
        while (tokenizer.hasMoreTokens())
        {
            String path = tokenizer.nextToken();
            Resource resource = getContext().newResource(path);

            // Resolve file path if possible
            File file = resource.getFile();
            if (file != null && isAcceptableLibrary(file, JAR_WITH_SUCH_CLASS_MUST_BE_EXCLUDED))
            {
                super.addClassPath(path);
            }
            else
            {
                __logger.info("Did not add " + path + " to the classloader of the webapp " + getContext());
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
            __logger.ignore(e);
        }
        return true;
    }

    private static Field _contextField;

    /**
     * In the case of the generation of a webapp via a jetty context file we
     * need a proper classloader to setup the app before we have the
     * WebappContext So we place a fake one there to start with. We replace it
     * with the actual webapp context with this method. We also apply the
     * extraclasspath there at the same time.
     *
     * @param webappContext the web app context
     */
    public void setWebappContext(WebAppContext webappContext)
    {
        try
        {
            if (_contextField == null)
            {
                _contextField = WebAppClassLoader.class.getDeclaredField("_context");
                _contextField.setAccessible(true);
            }
            _contextField.set(this, webappContext);
            if (webappContext.getExtraClasspath() != null)
            {
                addClassPath(webappContext.getExtraClasspath());
            }
        }
        catch (Throwable t)
        {
            // humf that will hurt if it does not work.
            __logger.warn("Unable to set webappcontext", t);
        }
    }
}
