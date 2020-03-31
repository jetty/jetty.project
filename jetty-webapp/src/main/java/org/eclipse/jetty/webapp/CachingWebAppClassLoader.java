//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A WebAppClassLoader that caches {@link #getResource(String)} and
 * {@link #loadAsResource(String, boolean)} results.
 * Specifically this ClassLoader caches not found classes and resources,
 * which can greatly increase performance for applications that search
 * for resources.
 */
@ManagedObject
public class CachingWebAppClassLoader extends WebAppClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    private static final Logger LOG = Log.getLogger(CachingWebAppClassLoader.class);

    private final Set<String> _notFound = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, URL> _cache = new ConcurrentHashMap<>();
    private volatile boolean useCache;
    
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
        if (!useCache)
        {
            return super.getResource(name);
        }
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
    public URL findResource(String name)
    {
        if (!useCache)
        {
            return super.findResource(name);
        }
        URL webappUrl = _cache.get(name);
        if (webappUrl == null)
        {
            webappUrl = super.findResource(name);
            if (webappUrl != null)
            {
                _cache.putIfAbsent(name, webappUrl);
            }
        }

        return webappUrl;
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
                    LOG.debug("Caching not found {}", name);
                    LOG.debug(nfe);
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

    public void setUseCache(boolean useCache)
    {
        this.useCache = useCache;
    }

    @Override
    public String toString()
    {
        return "Caching[" + super.toString() + "]";
    }

    /**
     * Lifecycle listener, that can be used from {@link WebAppContext#addLifeCycleListener(LifeCycle.Listener)}
     * to switch off the {@link #_cache} after web application has been started.
     */
    public static class ClearCacheLifeCycleListener extends AbstractLifeCycle.AbstractLifeCycleListener
    {
        protected ContextHandler container;

        public ClearCacheLifeCycleListener(ContextHandler container)
        {
            this.container = container;
        }

        @Override
        public void lifeCycleStarting(LifeCycle event)
        {
            lifeCycleContainer(event,"starting", true);
        }

        @Override
        public void lifeCycleStarted(LifeCycle event)
        {
            lifeCycleContainer(event,"started", false);
        }

        @Override
        public void lifeCycleFailure(LifeCycle event, Throwable cause)
        {
            lifeCycleContainer(event,"failure", false);
        }

        private void lifeCycleContainer(LifeCycle event, String action, boolean useCache)
        {
            if (event == container && container.getClassLoader() instanceof CachingWebAppClassLoader)
            {
                CachingWebAppClassLoader classLoader = (CachingWebAppClassLoader)container.getClassLoader();
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("{} using classloader cache in container [{}]",action,event);
                }
                classLoader.setUseCache(useCache);
                if (!useCache)
                {
                    classLoader.clearCache();
                }
            }
        }
    }
}
