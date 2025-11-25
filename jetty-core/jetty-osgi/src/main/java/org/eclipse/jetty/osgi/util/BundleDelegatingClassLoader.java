//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;

/**
 * BundleDelegatingClassLoader
 * <p>
 * A ClassLoader that delegates to an OSGi Bundle using the standard
 * OSGi specification API methods (Bundle.loadClass, Bundle.getResource,
 * Bundle.getResources) instead of using reflection to access internal
 * container-specific classloaders.
 * <p>
 * When the first class is loaded, this classloader obtains the real bundle
 * classloader from that class and delegates to it for subsequent operations,
 * providing better compatibility and performance for classloader-specific
 * operations.
 */
public class BundleDelegatingClassLoader extends ClassLoader implements BundleReference
{
    private final Bundle _bundle;
    private volatile ClassLoader _bundleClassLoader;

    /**
     * Creates a new BundleDelegatingClassLoader for the given bundle.
     *
     * @param bundle the OSGi bundle to delegate class and resource loading to
     */
    public BundleDelegatingClassLoader(Bundle bundle)
    {
        super(null);
        _bundle = bundle;
    }

    @Override
    public Bundle getBundle()
    {
        return _bundle;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        return loadClass(name, false);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        // Check if we already have a resolved bundle classloader
        ClassLoader cl = _bundleClassLoader;
        if (cl != null)
        {
            Class<?> clazz = cl.loadClass(name);
            if (resolve)
            {
                resolveClass(clazz);
            }
            return clazz;
        }
        
        // Load the class via Bundle API
        Class<?> clazz = _bundle.loadClass(name);
        
        // Try to acquire the real bundle classloader from the loaded class
        ClassLoader classLoader = clazz.getClassLoader();
        // Verify the classloader is for our bundle (implements BundleReference for same bundle)
        if (classLoader instanceof BundleReference bundleRef && _bundle.equals(bundleRef.getBundle()))
        {
            _bundleClassLoader = classLoader;
        }
        
        if (resolve)
        {
            resolveClass(clazz);
        }
        return clazz;
    }

    @Override
    public URL getResource(String name)
    {
        // If we have the real classloader, use it
        ClassLoader cl = _bundleClassLoader;
        if (cl != null)
        {
            return cl.getResource(name);
        }
        // Otherwise use the Bundle API
        return _bundle.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        // If we have the real classloader, use it
        ClassLoader cl = _bundleClassLoader;
        if (cl != null)
        {
            return cl.getResources(name);
        }
        // Otherwise use the Bundle API
        return _bundle.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name)
    {
        // If we have the real classloader, use it
        ClassLoader cl = _bundleClassLoader;
        if (cl != null)
        {
            return cl.getResourceAsStream(name);
        }
        // Otherwise get the resource URL and open a stream
        URL url = getResource(name);
        if (url != null)
        {
            try
            {
                return url.openStream();
            }
            catch (IOException e)
            {
                // Return null on IOException to follow ClassLoader.getResourceAsStream() contract
                // which returns null if the resource cannot be read
                return null;
            }
        }
        return null;
    }

    /**
     * Gets the underlying bundle classloader if one has been resolved,
     * or null if no class has been loaded yet.
     *
     * @return the resolved bundle classloader, or null
     */
    public ClassLoader getBundleClassLoader()
    {
        return _bundleClassLoader;
    }

    @Override
    public String toString()
    {
        return String.format("%s[bundle=%s]", getClass().getSimpleName(), _bundle.getSymbolicName());
    }
}
