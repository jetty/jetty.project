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

package org.eclipse.jetty.ee10.osgi.boot.utils;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGiClassLoader
 *
 * Class loader that is aware of a bundle. Similar to WebAppClassLoader from Jetty
 * and the OSGiWebAppClassLoader, but works without webapps.
 */
public class OSGiClassLoader extends URLClassLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(OSGiClassLoader.class);

    private Bundle _bundle;
    private ClassLoader _osgiBundleClassLoader;
    private ClassLoader _parent;

    public OSGiClassLoader(ClassLoader parent, Bundle bundle)
    {
        super(new URL[]{}, parent);
        _parent = getParent();
        _bundle = bundle;
        _osgiBundleClassLoader = BundleClassLoaderHelperFactory.getFactory().getHelper().getBundleClassLoader(_bundle);
    }

    /**
     * Get a resource from the classloader
     *
     * Copied from WebAppClassLoader
     */
    @Override
    public URL getResource(String name)
    {
        URL url = null;
        boolean triedParent = false;

        if (url == null)
        {
            url = _osgiBundleClassLoader.getResource(name);

            if (url == null && name.startsWith("/"))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("HACK leading / off {}", name);

                url = _osgiBundleClassLoader.getResource(name.substring(1));
            }
        }

        if (url == null && !triedParent)
        {
            if (_parent != null)
                url = _parent.getResource(name);
        }

        if (url != null)
            if (LOG.isDebugEnabled())
                LOG.debug("getResource({})={}", name, url);

        return url;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        return loadClass(name, false);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (getClassLoadingLock(name))
        {
            Class<?> c = findLoadedClass(name);
            ClassNotFoundException ex = null;
            boolean triedParent = false;

            if (c == null)
            {
                try
                {
                    c = this.findClass(name);
                }
                catch (ClassNotFoundException e)
                {
                    ex = e;
                }
            }

            if (c == null && _parent != null && !triedParent)
                c = _parent.loadClass(name);

            if (c == null)
                throw ex;

            if (resolve)
                resolveClass(c);

            if (LOG.isDebugEnabled())
                LOG.debug("loaded {} from {}", c, c.getClassLoader());

            return c;
        }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        Enumeration<URL> osgiUrls = _osgiBundleClassLoader.getResources(name);
        Enumeration<URL> urls = super.getResources(name);
        return Collections.enumeration(toList(osgiUrls, urls));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        return _osgiBundleClassLoader.loadClass(name);
    }

    /**
     *
     */
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
}
