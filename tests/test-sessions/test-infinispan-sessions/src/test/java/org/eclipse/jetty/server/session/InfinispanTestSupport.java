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


package org.eclipse.jetty.server.session;

import java.io.File;

import org.eclipse.jetty.util.IO;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * InfinispanTestSupport
 *
 *
 */
public class InfinispanTestSupport
{
    public static final String DEFAULT_CACHE_NAME =  "session_test_cache";
    public  Cache _cache;
 
    public ConfigurationBuilder _builder;
    private  File _tmpdir;
    private boolean _useFileStore;
    private String _name;
    public static  EmbeddedCacheManager _manager;
    
    static
    {
        try
        {
            _manager = new DefaultCacheManager(new GlobalConfigurationBuilder().globalJmxStatistics().allowDuplicateDomains(true).build());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    public InfinispanTestSupport ()
    {
        this (null);
    }
    
    public InfinispanTestSupport(String cacheName)
    {     
        if (cacheName == null)
            cacheName = DEFAULT_CACHE_NAME+System.currentTimeMillis();
        
        _name = cacheName;
        _builder = new ConfigurationBuilder();
    }
    
    public void setUseFileStore (boolean useFileStore)
    {
        _useFileStore = useFileStore;
    }
  
    public Cache getCache ()
    {
        return _cache;
    }
    
    public void setup () throws Exception
    {
       if (_useFileStore)
       {      
        _tmpdir = File.createTempFile("infini", "span");
        _tmpdir.delete();
        _tmpdir.mkdir();
        Configuration config = _builder.persistence().addSingleFileStore().location(_tmpdir.getAbsolutePath()).storeAsBinary().build();
        _manager.defineConfiguration(_name, config);
       }
       else
       {
           _manager.defineConfiguration(_name, _builder.build());
       }
       _cache = _manager.getCache(_name);
    }


    public void teardown () throws Exception
    {
        _manager.removeCache(_name);
        if (_useFileStore)
        {
            if (_tmpdir != null)
            {
                IO.delete(_tmpdir);
            }
        }
    }
}
