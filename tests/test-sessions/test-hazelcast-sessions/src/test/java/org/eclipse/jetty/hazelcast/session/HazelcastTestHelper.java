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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MulticastConfig;
import com.hazelcast.config.NetworkConfig;
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
    static final String _hazelcastInstanceName = "SESSION_TEST_"+Long.toString( TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    
    static final String _name = Long.toString( TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) );
    static final HazelcastInstance _instance = Hazelcast.getOrCreateHazelcastInstance( new Config() //
                                            .setInstanceName(_hazelcastInstanceName ) //
                                            .setNetworkConfig( new NetworkConfig().setJoin( //
                                                new JoinConfig().setMulticastConfig( //
                                                    new MulticastConfig().setEnabled( false ) ) ) ) //
                                            .addMapConfig( new MapConfig().setName(_name) ) );

    public HazelcastTestHelper ()
    {
        // noop
    }

    public SessionDataStoreFactory createSessionDataStoreFactory(boolean onlyClient)
    {
        HazelcastSessionDataStoreFactory factory = new HazelcastSessionDataStoreFactory();
        factory.setOnlyClient( onlyClient );
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
    
    public boolean checkSessionPersisted (SessionData data)
    {
        Object obj = _instance.getMap(_name).get(data.getContextPath() + "_" + data.getVhost() + "_" + data.getId());
        if (obj == null)
            return false;
        
        SessionData saved = (SessionData)obj;
        
        assertEquals(data.getId(),saved.getId());
        assertEquals(data.getContextPath(), saved.getContextPath());
        assertEquals(data.getVhost(), saved.getVhost());
        assertEquals(data.getLastNode(), saved.getLastNode());
        assertEquals(data.getCreated(), saved.getCreated());
        assertEquals(data.getAccessed(), saved.getAccessed());
        assertEquals(data.getLastAccessed(), saved.getLastAccessed());
        assertEquals(data.getCookieSet(), saved.getCookieSet());
        assertEquals(data.getExpiry(), saved.getExpiry());
        assertEquals(data.getMaxInactiveMs(), saved.getMaxInactiveMs());

        
        //same number of attributes
        assertEquals(data.getAllAttributes().size(),saved.getAllAttributes().size());
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
