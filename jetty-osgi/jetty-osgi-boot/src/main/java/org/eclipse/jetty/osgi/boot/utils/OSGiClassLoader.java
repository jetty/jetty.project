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

package org.eclipse.jetty.osgi.boot.utils;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.osgi.framework.Bundle;

/**
 * OSGiClassLoader
 *
 * Class loader that is aware of a bundle. Similar to WebAppClassLoader from Jetty
 * and the OSGiWebAppClassLoader, but works without webapps.
 */
public class OSGiClassLoader extends URLClassLoader
{
    private static final Logger LOG = Log.getLogger(OSGiClassLoader.class);
    
    
    private Bundle _bundle;
    private ClassLoader _osgiBundleClassLoader;
    private boolean _lookInOsgiFirst = true;
    private ClassLoader _parent;
    
    /* ------------------------------------------------------------ */
    public OSGiClassLoader(ClassLoader parent, Bundle bundle)
    {
        super(new URL[]{}, parent);
        _parent = getParent();
        _bundle = bundle;
        _osgiBundleClassLoader = BundleClassLoaderHelperFactory.getFactory().getHelper().getBundleClassLoader(_bundle);
    }
    
  
    
    /* ------------------------------------------------------------ */
    /**
     * Get a resource from the classloader
     * 
     * Copied from WebAppClassLoader
     */
    public URL getResource(String name)
    {
        URL url= null;
        boolean tried_parent= false;

        
        if (_parent!=null && !_lookInOsgiFirst)
        {
            tried_parent= true;
            
            if (_parent!=null)
                url= _parent.getResource(name);
        }

        if (url == null)
        {           
            url = _osgiBundleClassLoader.getResource(name);

            if (url == null && name.startsWith("/"))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("HACK leading / off " + name);

                url = _osgiBundleClassLoader.getResource(name.substring(1));
            }
        }

        if (url == null && !tried_parent)
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
        Class<?> c = findLoadedClass(name);
        ClassNotFoundException ex= null;
        boolean tried_parent= false;
        
        if (c == null && _parent!=null && !_lookInOsgiFirst)
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

        if (c == null && _parent!=null && !tried_parent)
            c = _parent.loadClass(name);

        if (c == null)
            throw ex;

        if (resolve)
            resolveClass(c);

        if (LOG.isDebugEnabled())
            LOG.debug("loaded " + c+ " from "+c.getClassLoader());
        
        return c;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        Enumeration<URL> osgiUrls = _osgiBundleClassLoader.getResources(name);
        Enumeration<URL> urls = super.getResources(name);
        if (_lookInOsgiFirst)
        {
            return Collections.enumeration(toList(osgiUrls, urls));
        }
        else
        {
            return Collections.enumeration(toList(urls, osgiUrls));
        }
    }
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        try
        {
            return _lookInOsgiFirst ? _osgiBundleClassLoader.loadClass(name) : super.findClass(name);
        }
        catch (ClassNotFoundException cne)
        {
            try
            {
                return _lookInOsgiFirst ? super.findClass(name) : _osgiBundleClassLoader.loadClass(name);
            }
            catch (ClassNotFoundException cne2)
            {
                throw cne;
            }
        }
    }
    
    
    
    

   /* ------------------------------------------------------------ */
    /**
     * @param e
     * @param e2
     * @return
     */
    private List<URL> toList(Enumeration<URL> e, Enumeration<URL> e2)
    {
        List<URL> list = new ArrayList<URL>();
        while (e != null && e.hasMoreElements())
            list.add(e.nextElement());
        while (e2 != null && e2.hasMoreElements())
            list.add(e2.nextElement());
        return list;
    }
}
