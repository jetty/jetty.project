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

package org.eclipse.jetty.ee9.webapp;

import java.io.IOException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A WebAppClassLoader that caches {@link #getResource(String)} results.
 * Specifically this ClassLoader caches not found classes and resources,
 * which can greatly increase performance for applications that search
 * for resources.
 */
@ManagedObject
public class CachingWebAppClassLoader extends WebAppClassLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(CachingWebAppClassLoader.class);

    private final Set<String> _notFound = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, URL> _cache = new ConcurrentHashMap<>();

    public CachingWebAppClassLoader(ClassLoader parent, Context context) throws IOException
    {
        super(parent, context);
    }

    public CachingWebAppClassLoader(Context context) throws IOException
    {
        super(context);
    }

    @Override
    public URL getResource(String name)
    {
        if (_notFound.contains(name))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not found cache hit resource {}", name);
            return null;
        }

        URL url = _cache.get(name);

        if (url == null)
        {
            // Not found in cache, try parent
            url = super.getResource(name);

            if (url == null)
            {
                // Still not found, cache the not-found result
                if (LOG.isDebugEnabled())
                    LOG.debug("Caching not found resource {}", name);
                _notFound.add(name);
            }
            else
            {
                // Cache the new result
                _cache.putIfAbsent(name, url);
            }
        }

        return url;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        if (_notFound.contains(name))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Not found cache hit resource {}", name);
            throw new ClassNotFoundException(name + ": in notfound cache");
        }
        try
        {
            return super.loadClass(name);
        }
        catch (ClassNotFoundException nfe)
        {
            if (_notFound.add(name))
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Caching not found {}", name, nfe);
                }
            throw nfe;
        }
    }

    @ManagedOperation
    public void clearCache()
    {
        _cache.clear();
        _notFound.clear();
    }

    @Override
    public String toString()
    {
        return "Caching[" + super.toString() + "]";
    }
}
