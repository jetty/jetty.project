//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;

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
        _cache.clear();
    }
    
    
    
    @SuppressWarnings("unchecked")
    public void createSession (SessionData data)
    throws Exception
    {
        _cache.put(data.getContextPath()+"_"+data.getVhost()+"_"+data.getId(), data);
    }

    
    public void createUnreadableSession (SessionData data)
    {
        
    }
    
    
    public boolean checkSessionExists (SessionData data)
    throws Exception
    {
        return (_cache.get(data.getContextPath()+"_"+data.getVhost()+"_"+data.getId()) != null);
    }
    
    
    public boolean checkSessionPersisted (SessionData data)
    throws Exception
    {
        Object obj = _cache.get(data.getContextPath()+"_"+data.getVhost()+"_"+data.getId());
        if (obj == null)
            return false;
        
        SessionData saved = (SessionData)obj;
        
        assertEquals(data.getId(), saved.getId());
        assertEquals(data.getContextPath(), saved.getContextPath());
        assertEquals(data.getVhost(), saved.getVhost());
        assertEquals(data.getAccessed(), saved.getAccessed());
        assertEquals(data.getLastAccessed(), saved.getLastAccessed());
        assertEquals(data.getCreated(), saved.getCreated());
        assertEquals(data.getCookieSet(), saved.getCookieSet());
        assertEquals(data.getLastNode(), saved.getLastNode());
        //don't test lastSaved because that is set on SessionData only after return from SessionDataStore.save()  
        assertEquals(data.getExpiry(), saved.getExpiry());
        assertEquals(data.getMaxInactiveMs(), saved.getMaxInactiveMs());

        //same number of attributes
        assertEquals(data.getAllAttributes().size(), saved.getAllAttributes().size());
        //same keys
        assertTrue(data.getKeys().equals(saved.getKeys()));
        //same values
        for (String name:data.getKeys())
        {
            assertTrue(data.getAttribute(name).equals(saved.getAttribute(name)));
        }
        
        return true;
    }
    
}
