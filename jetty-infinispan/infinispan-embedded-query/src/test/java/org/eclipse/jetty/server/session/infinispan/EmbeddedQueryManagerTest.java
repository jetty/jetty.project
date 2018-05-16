package org.eclipse.jetty.server.session.infinispan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.session.infinispan.EmbeddedQueryManager;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.hibernate.search.cfg.Environment;
import org.hibernate.search.cfg.SearchMapping;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.Index;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.Test;

public class EmbeddedQueryManagerTest
{
    public static final String DEFAULT_CACHE_NAME =  "session_test_cache";

    
    @Test
    public void test()
    {
        
        String _name = DEFAULT_CACHE_NAME+System.currentTimeMillis();
        ConfigurationBuilder _builder = new ConfigurationBuilder();
        EmbeddedCacheManager _manager;
        try
        {
            _manager = new DefaultCacheManager(new GlobalConfigurationBuilder().globalJmxStatistics().allowDuplicateDomains(true).build());
            System.err.println(_manager);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }
                
        
        //TODO verify that this is being indexed properly, if you change expiry to something that is not a valid field it still passes the tests
        SearchMapping mapping = new SearchMapping();
        mapping.entity(SessionData.class).indexed().providedId().property("expiry", ElementType.FIELD).field();
        Properties properties = new Properties();
        properties.put(Environment.MODEL_MAPPING, mapping);
        
        _manager.defineConfiguration(_name, _builder
                .build());
        
        
        Configuration dcc = _manager.getDefaultCacheConfiguration();
        Configuration c = new ConfigurationBuilder().read(dcc)
                                                    .indexing()
                                                    .index(Index.ALL)
                                                    .addIndexedEntity(SessionData.class)
                                                    .withProperties(properties)
                                                    .build();
        
        _manager.defineConfiguration(_name, c);
        Cache<String, SessionData> _cache = _manager.getCache(_name);                
        
        int numSessions = 10;
        long currentTime = 500;
        int maxExpiryTime = 1000;
        
        Set<String> expiredSessions = new HashSet<>();
        Random r = new Random();
        
        for(int i=0; i<numSessions; i++)
        {
            //create new sessiondata with random expiry time
            long expiryTime = r.nextInt(maxExpiryTime);
            SessionData sd = new SessionData("sd"+i, "", "", 0, 0, 0, 0);
            sd.setExpiry(expiryTime);
            
            //if this entry has expired add it to expiry list
            if (expiryTime <= currentTime)
            {
                expiredSessions.add("sd"+i);
            }
            
            //add to cache
            _cache.put("sd"+i,sd);
        }
       
        //run the query
        QueryManager qm = new EmbeddedQueryManager(_cache);
        Set<String> queryResult = qm.queryExpiredSessions(currentTime);
        
        // Check that the result is correct
        assertEquals(expiredSessions.size(), queryResult.size());
        for(String s : expiredSessions)
        {
            assertTrue(queryResult.contains(s));
        }
        
    }
}
