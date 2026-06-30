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

package org.eclipse.jetty.ee.common;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A [WebAppClassLoader] that caches found and not-found [#getResource(String)] results.
///
/// Specifically this `ClassLoader` caches not-found classes and resources, and found resources
/// (found classes are already cached by [java.net.URLClassLoader]), which can greatly increase
/// performance for applications that search for resources.
@ManagedObject
public class CachingWebAppClassLoader extends WebAppClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    private static final Logger LOG = LoggerFactory.getLogger(CachingWebAppClassLoader.class);

    private final Set<String> _notFound = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, URL> _cache = new ConcurrentHashMap<>();
    private final int _maxEntries;

    public CachingWebAppClassLoader(Context context) throws IOException
    {
        this(null, context);
    }

    public CachingWebAppClassLoader(ClassLoader parent, Context context) throws IOException
    {
        this(parent, context, 1024);
    }

    public CachingWebAppClassLoader(ClassLoader parent, Context context, int maxEntries) throws IOException
    {
        super(parent, context);
        if (maxEntries <= 0)
            throw new IllegalArgumentException("invalid max entries");
        this._maxEntries = maxEntries;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        // Cannot cache Enumerations: they are
        // not thread safe and cannot be rewound.
        return super.getResources(name);
    }

    @Override
    public URL getResource(String name)
    {
        if (_notFound.contains(name))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not-found cache hit resource {}", name);
            return null;
        }

        URL url = _cache.get(name);

        if (url == null)
        {
            // Not found in cache, try parent
            url = super.getResource(name);

            if (url == null)
            {
                // Still not found, cache the not-found result.
                if (LOG.isDebugEnabled())
                    LOG.debug("Caching not-found resource {}", name);
                addNotFound(name);
            }
            else
            {
                // Cache the new result.
                _cache.putIfAbsent(name, url);
            }
        }

        return url;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        Class<?> klass = findLoadedClass(name);
        if (klass != null)
            return klass;

        if (_notFound.contains(name))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not-found cache hit class {}", name);
            throw new ClassNotFoundException(name + ": in not-found cache");
        }
        try
        {
            return super.loadClass(name, resolve);
        }
        catch (ClassNotFoundException nfe)
        {
            addNotFound(name);
            if (LOG.isDebugEnabled())
                LOG.debug("Caching not-found class {}", name, nfe);
            throw nfe;
        }
    }

    private void addNotFound(String name)
    {
        // Racy, but innocuous.
        if (_notFound.size() > _maxEntries)
            _notFound.clear();
        _notFound.add(name);
    }

    @ManagedOperation
    public void clearCache()
    {
        _cache.clear();
        _notFound.clear();
    }

    @Override
    public void close() throws IOException
    {
        super.close();
        clearCache();
    }

    @Override
    public String toString()
    {
        return "Caching[" + super.toString() + "]";
    }
}
