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

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MulticastConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HazelcastTestHelper
 *
 *
 */
public class HazelcastTestHelper
{
    static final String _hazelcastInstanceName = "SESSION_TEST_"+Long.toString( TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));

    private  final static Logger LOG = Log.getLogger( HazelcastTestHelper.class);
    
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
        factory.setPredicate( DEFAULT_EXPIRED_SESSION_PREDICATE );
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

    public static final HazelcastSessionDataStore.ExpiredSessionPredicate<String, SessionData>
        DEFAULT_EXPIRED_SESSION_PREDICATE = new HazelcastSessionDataStore.ExpiredSessionPredicate<String, SessionData>()
    {
        private HazelcastSessionDataStore.HazelcastSessionDataStoreContext context;

        @Override
        public void setHazelcastSessionDataStoreContext(
            HazelcastSessionDataStore.HazelcastSessionDataStoreContext context )
        {
            this.context = context;
        }

        @Override
        public boolean apply( Map.Entry<String, SessionData> mapEntry )
        {
            SessionData sessionData = mapEntry.getValue();
            long now = System.currentTimeMillis();
            if ( context.getSessionContext().getWorkerName().equals( sessionData.getLastNode() ) )
            {
                //we are its manager, add it to the expired set if it is expired now
                if ( ( sessionData.getExpiry() > 0 ) && sessionData.getExpiry() <= now )
                {
                    if ( LOG.isDebugEnabled() )
                    {
                        LOG.debug( "Session {} managed by {} is expired", sessionData.getId(),
                                   context.getSessionContext().getWorkerName() );
                    }
                    return true;
                }
            }
            else
            {
                //if we are not the session's manager, only expire it iff:
                // this is our first expiryCheck and the session expired a long time ago
                //or
                //the session expired at least one graceperiod ago
                if ( context.getLastExpiryCheckTime() <= 0 )
                {
                    if ( ( sessionData.getExpiry() > 0 ) //
                        && sessionData.getExpiry() < ( now - ( 1000L * ( 3 * context.getGracePeriodSec() ) ) ) )
                    {
                        return true;
                    }
                }
                else
                {
                    if ( ( sessionData.getExpiry() > 0 ) //
                        && sessionData.getExpiry() < ( now - ( 1000L * context.getGracePeriodSec() ) ) )
                    {
                        return true;
                    }
                }
            }
            return false;
        }
    };


}
