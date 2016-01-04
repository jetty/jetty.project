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


package org.eclipse.jetty.server.session.remote;

import java.io.File;

import org.eclipse.jetty.util.IO;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * RemoteInfinispanTestSupport
 *
 *
 */
public class RemoteInfinispanTestSupport
{
    public static final String DEFAULT_CACHE_NAME =  "session_test_cache";
    public  RemoteCache _cache;
    private String _name;
    public static  RemoteCacheManager _manager;
    
    static
    {
        try
        {
            _manager = new RemoteCacheManager();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    public RemoteInfinispanTestSupport ()
    {
        this (null);
    }
    
    public RemoteInfinispanTestSupport(String cacheName)
    {     
        if (cacheName == null)
            cacheName = DEFAULT_CACHE_NAME+System.currentTimeMillis();
        
        _name = cacheName;
    }
    
 
  
    public RemoteCache getCache ()
    {
        return _cache;
    }
    
    public void setup () throws Exception
    {
       _cache = _manager.getCache(_name);
    }


    public void teardown () throws Exception
    {
        
    }
}
