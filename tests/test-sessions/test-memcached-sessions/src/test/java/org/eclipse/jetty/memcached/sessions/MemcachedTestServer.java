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


package org.eclipse.jetty.memcached.sessions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.memcached.session.MemcachedSessionDataMap;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.eclipse.jetty.server.session.CachingSessionDataStore;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionHandler;

/**
 * MemcachedTestServer
 *
 *
 */
public class MemcachedTestServer extends AbstractTestServer
{

    public static class MockDataStore extends AbstractSessionDataStore
    {
        private Map<String,SessionData> _store = new HashMap<>();
        private int _loadCount = 0;
        
        
        /** 
         * @see org.eclipse.jetty.server.session.SessionDataStore#isPassivating()
         */
        @Override
        public boolean isPassivating()
        {
            return true;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataStore#exists(java.lang.String)
         */
        @Override
        public boolean exists(String id) throws Exception
        {
            return _store.get(id) != null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#load(java.lang.String)
         */
        @Override
        public SessionData load(String id) throws Exception
        {
            _loadCount++;
            return _store.get(id);
        }
        
        public void zeroLoadCount()
        {
            _loadCount = 0;
        }
        
        public int getLoadCount()
        {
            return _loadCount;
        }

        /** 
         * @see org.eclipse.jetty.server.session.SessionDataMap#delete(java.lang.String)
         */
        @Override
        public boolean delete(String id) throws Exception
        {
            return (_store.remove(id) != null);
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doStore(java.lang.String, org.eclipse.jetty.server.session.SessionData, long)
         */
        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            _store.put(id, data);
            
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionDataStore#doGetExpired(java.util.Set)
         */
        @Override
        public Set<String> doGetExpired(Set<String> candidates)
        {
            Set<String> expiredIds = new HashSet<>();
            long now = System.currentTimeMillis();
            if (candidates != null)
            {
                for (String id:candidates)
                {
                    SessionData sd = _store.get(id);
                    if (sd == null)
                        expiredIds.add(id);
                    else if (sd.isExpiredAt(now))
                        expiredIds.add(id);
                }
            }
            
            for (String id:_store.keySet())
            {
                SessionData sd = _store.get(id);
                if (sd.isExpiredAt(now))
                    expiredIds.add(id);
            }
            
            return expiredIds;
        }

        @Override
        protected void doStop() throws Exception
        {
            super.doStop();
        }
        
        
        
    }
    /**
     * @param port
     * @param maxInactivePeriod
     * @param scavengePeriod
     * @param evictionPolicy
     */
    public MemcachedTestServer(int port, int maxInactivePeriod, int scavengePeriod, int evictionPolicy) throws Exception
    {
        super(port, maxInactivePeriod, scavengePeriod, evictionPolicy);
    }



    /** 
     * @see org.eclipse.jetty.server.session.AbstractTestServer#newSessionHandler()
     */
    @Override
    public SessionHandler newSessionHandler()
    {
        SessionHandler handler =  new SessionHandler();
        handler.setSessionIdManager(_sessionIdManager);
        MockDataStore persistentStore = new MockDataStore();
        MemcachedSessionDataMap sdm = new MemcachedSessionDataMap("localhost", "11211");
        CachingSessionDataStore cachingStore = new CachingSessionDataStore(sdm, persistentStore);
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        ss.setSessionDataStore(cachingStore);
        handler.setSessionCache(ss);
        return handler;
    }

}
