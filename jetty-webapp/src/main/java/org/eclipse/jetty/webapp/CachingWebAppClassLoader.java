//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.ConcurrentHashSet;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * A WebAppClassLoader that caches {@link #getResource(String)} results.
 * Specifically this ClassLoader caches not found classes and resources,
 * which can greatly increase performance for applications that search 
 * for resources.
 */
@ManagedObject
public class CachingWebAppClassLoader extends WebAppClassLoader
{
    private static final Logger LOG = Log.getLogger(CachingWebAppClassLoader.class);
    
    private final ConcurrentHashSet<String> _notFound = new ConcurrentHashSet<>();
    private final ConcurrentHashMap<String,URL> _cache = new ConcurrentHashMap<>();
    
    public CachingWebAppClassLoader(ClassLoader parent, Context context) throws IOException
    {
        super(parent,context);
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
                LOG.debug("Not found cache hit resource {}",name);
            return null;
        }
        
        URL url = _cache.get(name);
        
        if (name==null)
        {
            url = super.getResource(name);
        
            if (url==null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Caching not found resource {}",name);
                _notFound.add(name);
            }
            else
            {
                _cache.putIfAbsent(name,url);
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
                LOG.debug("Not found cache hit resource {}",name);
            throw new ClassNotFoundException(name+": in notfound cache");
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
                    LOG.debug("Caching not found {}",name);
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
    
    @Override
    public String toString()
    {
        return "Caching["+super.toString()+"]";
    }
}
