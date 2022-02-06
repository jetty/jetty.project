//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session.infinispan;

import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.EmbeddedQueryManager;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmbeddedQueryManagerTest
{
    public static final String DEFAULT_CACHE_NAME = "session_test_cache";
    private static final Random r = new Random();
    private static final int NUM_SESSIONS = 10;
    private static int count = 0;
    private static final int MAX_EXPIRY_TIME = 1000;

    @Test
    public void test()
    {
        String name = DEFAULT_CACHE_NAME + System.currentTimeMillis();
        EmbeddedCacheManager cacheManager = new DefaultCacheManager(new GlobalConfigurationBuilder().jmx().build());

        //TODO verify that this is being indexed properly, if you change expiry to something that is not a valid field it still passes the tests
        SearchMapping mapping = new SearchMapping();
        mapping.entity(InfinispanSessionData.class).indexed().property("expiry", ElementType.FIELD).field();

        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);
        properties.put("hibernate.search.default.indexBase", MavenTestingUtils.getTargetTestingDir().getAbsolutePath());

        Configuration dcc = cacheManager.getDefaultCacheConfiguration();
        ConfigurationBuilder b = new ConfigurationBuilder();
        if (dcc != null)
            b = b.read(dcc);

        b.indexing().addIndexedEntity(InfinispanSessionData.class).withProperties(properties);
        Configuration c = b.build();

        cacheManager.defineConfiguration(name, c);
        Cache<String, InfinispanSessionData> cache = cacheManager.getCache(name);

        //put some sessions into the cache for "foo" context
        ContextHandler fooHandler = new ContextHandler();
        fooHandler.setContextPath("/foo");
        SessionContext fooSessionContext = new SessionContext("w0", fooHandler.getServletContext());
        Set<InfinispanSessionData> fooSessions = createSessions(cache, fooSessionContext);

        //put some sessions into the cache for "bar" context
        ContextHandler barHandler = new ContextHandler();
        barHandler.setContextPath("/bar");
        SessionContext barSessionContext = new SessionContext("w0", barHandler.getServletContext());
        Set<InfinispanSessionData> barSessions = createSessions(cache, barSessionContext);

        int time = 500;

        //run the query for "foo" context
        checkResults(cache, fooSessionContext, time, fooSessions);

        //run the query for the "bar" context
        checkResults(cache, barSessionContext, time, barSessions);
    }

    private Set<InfinispanSessionData> createSessions(Cache<String, InfinispanSessionData> cache, SessionContext sessionContext)
    {
        Set<InfinispanSessionData> sessions = new HashSet<>();

        for (int i = 0; i < NUM_SESSIONS; i++)
        {
            //create new sessiondata with random expiry time
            long expiryTime = r.nextInt(MAX_EXPIRY_TIME);
            String id = "sd" + count;
            ++count;
            InfinispanSessionData sd = new InfinispanSessionData(id, sessionContext.getCanonicalContextPath(), sessionContext.getVhost(), 0, 0, 0, 0);
            sd.setExpiry(expiryTime);
            sessions.add(sd);
            //add to cache
            cache.put(id, sd);
        }
        return sessions;
    }

    private void checkResults(Cache<String, InfinispanSessionData> cache, SessionContext sessionContext, int time, Set<InfinispanSessionData> sessions)
    {
        QueryManager qm = new EmbeddedQueryManager(cache);
        Set<String> queryResult = qm.queryExpiredSessions(sessionContext, time);

        for (SessionData s : sessions)
        {
            if (s.getExpiry() > 0 && s.getExpiry() <= time)
            {
                assertTrue(queryResult.remove(s.getId()));
            }
        }
        assertTrue(queryResult.isEmpty()); //check we got them all
    }
}
