//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import org.eclipse.jetty.memcached.session.MemcachedSessionDataMapFactory;
import org.eclipse.jetty.server.session.AbstractSessionDataStore;
import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.CachingSessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

/**
 * MemcachedTestHelper
 */
public class MemcachedTestHelper
{

    public static class MockDataStore extends AbstractSessionDataStore
    {
        private Map<String, SessionData> _store = new HashMap<>();
        private int _loadCount = 0;

        @Override
        public boolean isPassivating()
        {
            return true;
        }

        @Override
        public boolean exists(String id) throws Exception
        {
            return _store.get(id) != null;
        }

        @Override
        public SessionData doLoad(String id) throws Exception
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

        @Override
        public boolean delete(String id) throws Exception
        {
            return (_store.remove(id) != null);
        }

        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            _store.put(id, data);
        }

        @Override
        public Set<String> doGetExpired(Set<String> candidates)
        {
            Set<String> expiredIds = new HashSet<>();
            long now = System.currentTimeMillis();
            if (candidates != null)
            {
                for (String id : candidates)
                {
                    SessionData sd = _store.get(id);
                    if (sd == null)
                        expiredIds.add(id);
                    else if (sd.isExpiredAt(now))
                        expiredIds.add(id);
                }
            }

            for (String id : _store.keySet())
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

    public static class MockDataStoreFactory extends AbstractSessionDataStoreFactory
    {

        /**
         * @see org.eclipse.jetty.server.session.SessionDataStoreFactory#getSessionDataStore(org.eclipse.jetty.server.session.SessionHandler)
         */
        @Override
        public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception
        {
            return new MockDataStore();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(MemcachedTestHelper.class);

    private static final Logger MEMCACHED_LOG = LoggerFactory.getLogger("org.eclipse.jetty.memcached.sessions.MemcachedLogs");

    static GenericContainer memcached =
        new GenericContainer("memcached:" + System.getProperty("memcached.docker.version", "1.6.6"))
            .withLogConsumer(new Slf4jLogConsumer(MEMCACHED_LOG));

    static
    {
        try
        {
            long start = System.currentTimeMillis();
            memcached.start();
            LOG.info("time to start memcache instance {}ms on {}:{}", System.currentTimeMillis() - start,
                     memcached.getHost(), memcached.getMappedPort(11211));
        }
        catch (Exception e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static SessionDataStoreFactory newSessionDataStoreFactory()
    {
        MockDataStoreFactory storeFactory = new MockDataStoreFactory();
        MemcachedSessionDataMapFactory mapFactory = new MemcachedSessionDataMapFactory();
        String host = memcached.getContainerIpAddress();
        int port = memcached.getMappedPort(11211);
        InetSocketAddress inetSocketAddress = new InetSocketAddress(host, port);
        mapFactory.setAddresses(inetSocketAddress);

        try
        {
            XMemcachedClientBuilder builder = new XMemcachedClientBuilder(Arrays.asList(inetSocketAddress));
            builder.build().flushAll();
        }
        catch (Exception e)
        {
            LOG.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }

        CachingSessionDataStoreFactory factory = new CachingSessionDataStoreFactory();
        factory.setSessionDataMapFactory(mapFactory);
        factory.setSessionStoreFactory(storeFactory);
        return factory;
    }
}
