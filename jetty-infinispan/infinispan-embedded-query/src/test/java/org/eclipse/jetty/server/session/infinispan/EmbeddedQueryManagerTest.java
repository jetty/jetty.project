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

package org.eclipse.jetty.server.session.infinispan;

import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.EmbeddedQueryManager;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.Index;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmbeddedQueryManagerTest
{
    public static final String DEFAULT_CACHE_NAME = "session_test_cache";

    @Test
    public void test()
    {
        String name = DEFAULT_CACHE_NAME + System.currentTimeMillis();
        EmbeddedCacheManager cacheManager = new DefaultCacheManager(new GlobalConfigurationBuilder().globalJmxStatistics().allowDuplicateDomains(true).build());

        //TODO verify that this is being indexed properly, if you change expiry to something that is not a valid field it still passes the tests
        SearchMapping mapping = new SearchMapping();
        mapping.entity(SessionData.class).indexed().providedId().property("expiry", ElementType.FIELD).field();
        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);
        properties.put("hibernate.search.default.indexBase", MavenTestingUtils.getTargetTestingDir().getAbsolutePath());

        Configuration dcc = cacheManager.getDefaultCacheConfiguration();
        ConfigurationBuilder b = new ConfigurationBuilder();
        if (dcc != null)
            b = b.read(dcc);

        b.indexing().index(Index.ALL).addIndexedEntity(SessionData.class).withProperties(properties);
        Configuration c = b.build();

        cacheManager.defineConfiguration(name, c);
        Cache<String, SessionData> cache = cacheManager.getCache(name);

        //put some sessions into the cache
        int numSessions = 10;
        long currentTime = 500;
        int maxExpiryTime = 1000;
        Set<String> expiredSessions = new HashSet<>();
        Random r = new Random();

        for (int i = 0; i < numSessions; i++)
        {
            //create new sessiondata with random expiry time
            long expiryTime = r.nextInt(maxExpiryTime);
            SessionData sd = new SessionData("sd" + i, "", "", 0, 0, 0, 0);
            sd.setExpiry(expiryTime);

            //if this entry has expired add it to expiry list
            if (expiryTime <= currentTime)
                expiredSessions.add("sd" + i);

            //add to cache
            cache.put("sd" + i, sd);
        }

        //run the query
        QueryManager qm = new EmbeddedQueryManager(cache);
        Set<String> queryResult = qm.queryExpiredSessions(currentTime);

        // Check that the result is correct
        assertEquals(expiredSessions.size(), queryResult.size());
        for (String s : expiredSessions)
        {
            assertTrue(queryResult.contains(s));
        }
    }
}
