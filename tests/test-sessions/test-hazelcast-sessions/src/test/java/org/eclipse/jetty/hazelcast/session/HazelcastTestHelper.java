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


package org.eclipse.jetty.hazelcast.session;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

/**
 * HazelcastTestHelper
 *
 *
 */
public class HazelcastTestHelper
{
    String _hazelcastInstanceName = "SESSION_TEST_"+Long.toString( TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    
    String _name = Long.toString( TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) );
    HazelcastInstance _instance;

    
    public HazelcastTestHelper ()
    {
        MapConfig mapConfig = new MapConfig();
        mapConfig.setName(_name);
        Config config = new Config();
        config.setInstanceName(_hazelcastInstanceName );
        config.addMapConfig( mapConfig );
        
        _instance = Hazelcast.getOrCreateHazelcastInstance( config );
    }
    
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        HazelcastSessionDataStoreFactory factory = new HazelcastSessionDataStoreFactory();
        factory.setMapName(_name);
        factory.setHazelcastInstance(_instance);
        
        return factory;
    }
    
   
    public void tearDown()
    {
        _instance.getMap(_name).clear();
    }
    
    public void createSession (SessionData data)
    {
        _instance.getMap(_name).put(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId(), data);
    }
    
    public boolean checkSessionExists (SessionData data)
    {
        return (_instance.getMap(_name).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId()) != null);
    }
}
