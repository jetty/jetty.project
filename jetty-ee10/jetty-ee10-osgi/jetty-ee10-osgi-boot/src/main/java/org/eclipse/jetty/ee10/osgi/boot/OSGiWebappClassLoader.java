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

package org.eclipse.jetty.ee10.osgi.boot;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.osgi.util.BundleClassLoaderHelperFactory;
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
     * @see org.eclipse.jetty.ee10.webapp.WebAppClassLoader#loadAsResource(java.lang.String, boolean)
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
}
