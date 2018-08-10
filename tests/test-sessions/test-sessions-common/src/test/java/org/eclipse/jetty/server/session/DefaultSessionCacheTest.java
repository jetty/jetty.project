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


package org.eclipse.jetty.server.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

/**
 * DefaultSessionCacheTest
 *
 *
 */
public class DefaultSessionCacheTest
{

    public static class TestSessionActivationListener implements HttpSessionActivationListener
    {
        public int passivateCalls  = 0;
        public int activateCalls = 0;

        @Override
        public void sessionWillPassivate(HttpSessionEvent se)
        {
            ++passivateCalls;
        }

        @Override
        public void sessionDidActivate(HttpSessionEvent se)
        {
           ++activateCalls;
        }
        
    }


    /**
     * Test sessions are saved when shutdown with a store.
     * 
     * @throws Exception
     */
    @Test
    public void testShutdownWithSessionStore()
    throws Exception
    {
        Server server = new Server();
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
        context.setContextPath("/test");
        context.setServer(server);
        
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
        
        TestSessionDataStore store = new TestSessionDataStore(true);//fake passivation
        cache.setSessionDataStore(store);       
        context.getSessionHandler().setSessionCache(cache);
        
        context.start();
        
        //put a session in the cache and store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        TestSessionActivationListener listener = new TestSessionActivationListener();
        cache.put("1234", session);
        assertTrue(cache.contains("1234"));
        session.setAttribute("aaa", listener);
        cache.put("1234", session);
        
        assertTrue(store.exists("1234"));
        assertTrue(cache.contains("1234"));
        
        context.stop(); //calls shutdown
        
        assertTrue(store.exists("1234"));
        assertFalse(cache.contains("1234"));
        assertEquals(2, listener.passivateCalls);
        assertEquals(1, listener.activateCalls);
    }
  
    
 

   /**
    * Test that a new Session object can be created from
    * previously persisted data (SessionData).
    * @throws Exception
    */
   @Test
   public void testNewSessionFromPersistedData()
           throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);
       
       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore(true);//fake passivation
       cache.setSessionDataStore(store);       
       context.getSessionHandler().setSessionCache(cache);
       
       context.start();
       
       long now = System.currentTimeMillis();
       //fake persisted data
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       Session session = cache.newSession(data);
       assertNotNull(session);
       assertEquals("1234", session.getId());
   }

   /**
    * Test that a session id can be renewed.
    * 
    * @throws Exception
    */
   @Test
   public void testRenewSessionId()
           throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);
       
       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore(true);//fake passivation
       cache.setSessionDataStore(store);       
       context.getSessionHandler().setSessionCache(cache);
       
       context.start();
       
       //put a session in the cache and store
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       Session session = cache.newSession(data);
       cache.put("1234", session);
       assertTrue(cache.contains("1234"));

       cache.renewSessionId("1234", "5678");
       
       assertTrue(cache.contains("5678"));
       assertFalse(cache.contains("1234"));
       
       assertTrue(store.exists("5678"));
       assertFalse(store.exists("1234"));
   }
    


   /**
    * Test that a session that is in the cache can be retrieved.
    * 
    * @throws Exception
    */
   @Test
   public void testGetSessionInCache()
           throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       Session session = cache.newSession(data);
       
       //put the session in the cache
       cache.put("1234", session);
       
       assertNotNull(cache.get("1234"));
   }

   /**
    * Test that the cache can load from the SessionDataStore
    * @throws Exception
    */
   @Test
   public void testGetSessionNotInCache()
           throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       //put session data into the store
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       store.store("1234", data);
       
       assertFalse(cache.contains("1234"));
       
       Session session = cache.get("1234");
       assertNotNull(session);
       assertEquals("1234", session.getId());
       assertEquals(now-20, session.getCreationTime());
   }

   @Test
   public void testPutRequestsStillActive()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       //make a session
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       data.setExpiry(now+TimeUnit.DAYS.toMillis(1));
       Session session = cache.newSession(data);
       session.access(now); //simulate request still active
       cache.put("1234", session);
       assertTrue(session.isResident());
       assertTrue(cache.contains("1234"));
       assertFalse(store.exists("1234"));
       
   }
    
   @Test
   public void testPutLastRequest()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       //make a session
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       data.setExpiry(now+TimeUnit.DAYS.toMillis(1));
       Session session = cache.newSession(data);
       session.access(now); //simulate request still active
       session.complete(); //simulate request exiting
       cache.put("1234", session);
       assertTrue(session.isResident());
       assertTrue(cache.contains("1234"));
       assertTrue(store.exists("1234"));

   }

   /**
    * Test contains method.
    * 
    * @throws Exception
    */
   @Test
   public void testContains()
           throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();

       //test one that isn't contained
       assertFalse(cache.contains("1234"));
       
       //test one that is contained
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       Session session = cache.newSession(data);
       
       //put the session in the cache
       cache.put("1234", session);
       assertTrue(cache.contains("1234"));
   }

   /**
    * Test the exist method.
    * 
    * @throws Exception
    */
   @Test
   public void testExists()
           throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start(); 
       
       //test one that doesn't exist at all
       assertFalse(cache.exists("1234"));
       
       //test one that only exists in the store
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       store.store("1234", data); 
       assertTrue(cache.exists("1234"));

       //test one that exists in the cache also
       Session session = cache.newSession(data);
       cache.put("1234", session);
       assertTrue(cache.exists("1234"));
   }


   /**
    * Test the delete method.
    * 
    * @throws Exception
    */
   @Test
   public void testDelete()
           throws Exception
   {       
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start(); 

       //test remove non-existent session
       Session session = cache.delete("1234");
       assertNull(session);

       //test remove of existing session in store only
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       store.store("1234", data);
       session = cache.delete("1234");
       assertNotNull(session);
       assertFalse(store.exists("1234"));
       assertFalse(cache.contains("1234"));

       //test remove of session in both store and cache
       data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       session = cache.newSession(data);
       cache.put("1234", session);
       assertTrue(store.exists("1234"));
       assertTrue(cache.contains("1234"));
       session = cache.delete("1234");
       assertNotNull(session);
       assertFalse(store.exists("1234"));
       assertFalse(cache.contains("1234"));
   }
    
    

   @Test
   public void testExpiration()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());
       
       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start(); 
       
       //test no candidates, no data in store      
       Set<String> result = cache.checkExpiration(Collections.emptySet());
       assertTrue(result.isEmpty());
       
       //test candidates that are in the cache and NOT expired
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       data.setExpiry(now+TimeUnit.DAYS.toMillis(1));
       Session session = cache.newSession(data);
       cache.put("1234", session);
       assertTrue(cache.exists("1234"));
       result = cache.checkExpiration(Collections.singleton("1234"));
       assertTrue(result.isEmpty());
       
       //test candidates that are in the cache AND expired
       data.setExpiry(1);
       cache.put("1234", session);
       result = cache.checkExpiration(Collections.singleton("1234"));
       assertEquals(1, result.size());
       assertEquals("1234", result.iterator().next());
       
       //test candidates that are not in the cache
       SessionData data2 = store.newSessionData("567", now-50, now-40, now-30, TimeUnit.MINUTES.toMillis(10));
       data2.setExpiry(1);
       store.store("567", data2);
       
       result = cache.checkExpiration(Collections.emptySet());
       assertNotNull(result);
       assertEquals(2, result.size());
       assertTrue(result.contains("1234"));
       assertTrue(result.contains("567"));
   }

   @Test
   public void testCheckInactiveSession()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();

       //test NEVER EVICT 
       //test session that is not resident
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       data.setExpiry(now+TimeUnit.DAYS.toMillis(1));
       Session session = cache.newSession(data);
       cache.checkInactiveSession(session);
       assertFalse(store.exists("1234"));
       assertFalse(cache.contains("1234"));
       assertFalse(session.isResident());
       //ie nothing happens to the session
       
       //test session that is resident but not valid
       cache.put("1234", session);
       session._state = Session.State.INVALID;
       cache.checkInactiveSession(session);
       assertTrue(store.exists("1234"));
       assertTrue(cache.contains("1234"));
       assertTrue(session.isResident());
       assertFalse(session.isValid());
       //ie nothing happens to the session
       
       //test session that is resident, is valid, but NEVER_EVICT
       session._state = Session.State.VALID;
       cache.checkInactiveSession(session);
       assertTrue(store.exists("1234"));
       assertTrue(cache.contains("1234"));
       assertTrue(session.isResident());
       assertTrue(session.isValid());
       //ie nothing happens to the session
       
       //test EVICT_ON_INACTIVITY, session has passed the inactivity time
       cache.setEvictionPolicy(SessionCache.EVICT_ON_INACTIVITY);
       data.setAccessed(now-TimeUnit.SECONDS.toMillis(30));
       cache.checkInactiveSession(session);
       assertFalse(cache.contains("1234"));
       assertFalse(session.isResident());
       
       //test  EVICT_ON_SESSION_EXIT with requests still active.
       //this should not affect the session because it this is an idle test only
       SessionData data2 = store.newSessionData("567", now, now-TimeUnit.SECONDS.toMillis(30), now-TimeUnit.SECONDS.toMillis(40), TimeUnit.MINUTES.toMillis(10));
       data2.setExpiry(now+TimeUnit.DAYS.toMillis(1));//not expired
       Session session2 = cache.newSession(data2);
       cache.put("567", session2);//ensure session is in cache
       cache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
       session2.access(System.currentTimeMillis());//simulate 1 request in session
       assertTrue(cache.contains("567")); 
       cache.checkInactiveSession(session2);
       assertTrue(cache.contains("567")); //not evicted


       //test  EVICT_ON_SESSION_EXIT - requests not active
       //this should not affect the session because this is an idle test only
       session2.complete(); //simulate last request leaving session
       cache.checkInactiveSession(session2);
       assertTrue(cache.contains("567"));

   }

   @Test
   public void testSaveOnEviction ()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_INACTIVITY); //evict after 1 second inactivity
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       //make a session
       long now = System.currentTimeMillis();
       SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
       data.setExpiry(now+TimeUnit.DAYS.toMillis(1));
       Session session = cache.newSession(data);
       cache.put("1234", session); //make it resident
       assertTrue(cache.contains("1234"));
       long accessed = now-TimeUnit.SECONDS.toMillis(30); //make it idle
       data.setAccessed(accessed);
       cache.checkInactiveSession(session);
       assertFalse(cache.contains("1234"));
       assertFalse(session.isResident());
       SessionData retrieved = store.load("1234");
       assertEquals(accessed, retrieved.getAccessed()); //check that we persisted the session before we evicted
   }

   @Test
   public void testSaveOnCreateTrue ()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       cacheFactory.setSaveOnCreate(true);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       long now = System.currentTimeMillis();
       cache.newSession(null, "1234", now,  TimeUnit.MINUTES.toMillis(10));
       assertTrue(store.exists("1234"));
   }
   
   
   @Test
   public void testSaveOnCreateFalse ()
   throws Exception
   {
       Server server = new Server();

       ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
       context.setContextPath("/test");
       context.setServer(server);

       DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
       cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
       cacheFactory.setSaveOnCreate(false);
       DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

       TestSessionDataStore store = new TestSessionDataStore();
       cache.setSessionDataStore(store);
       context.getSessionHandler().setSessionCache(cache);
       context.start();
       
       long now = System.currentTimeMillis();
       cache.newSession(null, "1234", now,  TimeUnit.MINUTES.toMillis(10));
       assertFalse(store.exists("1234"));
   }
   
 
}
