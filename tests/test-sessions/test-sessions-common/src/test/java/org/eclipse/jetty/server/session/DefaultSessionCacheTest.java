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

package org.eclipse.jetty.server.session;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DefaultSessionCacheTest
 */
public class DefaultSessionCacheTest extends AbstractSessionCacheTest
{

    @Override
    public AbstractSessionCacheFactory newSessionCacheFactory(int evictionPolicy, boolean saveOnCreate,
                                                              boolean saveOnInactiveEvict, boolean removeUnloadableSessions,
                                                              boolean flushOnResponseCommit)
    {
        DefaultSessionCacheFactory factory = new DefaultSessionCacheFactory();
        factory.setEvictionPolicy(evictionPolicy);
        factory.setSaveOnCreate(saveOnCreate);
        factory.setSaveOnInactiveEvict(saveOnInactiveEvict);
        factory.setRemoveUnloadableSessions(removeUnloadableSessions);
        factory.setFlushOnResponseCommit(flushOnResponseCommit);
        return factory;
    }
    
    @Override
    public void checkSessionBeforeShutdown(String id,
                                           SessionDataStore store,
                                           SessionCache cache,
                                           TestSessionActivationListener activationListener,
                                           TestHttpSessionListener sessionListener) throws Exception
    {
        assertTrue(store.exists(id));
        assertTrue(cache.contains(id));
        assertFalse(sessionListener.destroyedSessions.contains(id));
        assertEquals(1, activationListener.passivateCalls);
        assertEquals(1, activationListener.activateCalls);
    }
    
    @Override
    public void checkSessionAfterShutdown(String id,
                                          SessionDataStore store,
                                          SessionCache cache,
                                          TestSessionActivationListener activationListener,
                                          TestHttpSessionListener sessionListener) throws Exception
    {
        if (cache.isInvalidateOnShutdown())
        {
            assertFalse(store.exists(id));
            assertFalse(cache.contains(id));
            assertTrue(sessionListener.destroyedSessions.contains(id));
        }
        else
        {
            assertTrue(store.exists(id));
            assertFalse(cache.contains(id));
            assertEquals(2, activationListener.passivateCalls);
            assertEquals(1, activationListener.activateCalls); //no re-activate on shutdown
        }
    }

    @Test
    public void testRenewWithInvalidate() throws Exception
    {
        //Test that invalidation happens on ALL copies of the session that are in-use by requests
        int inactivePeriod = 20;
        int scavengePeriod = 3;

        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);

        SessionDataStoreFactory storeFactory = new TestSessionDataStoreFactory();
        TestServer server = new TestServer(0, inactivePeriod, scavengePeriod, cacheFactory, storeFactory);
        ServletContextHandler contextHandler = server.addContext("/test");
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server.getServerConnector().addBean(scopeListener);

        TestHttpSessionListener listener = new TestHttpSessionListener();
        contextHandler.getSessionHandler().addEventListener(listener);

        try
        {
            server.start();

            //Make a session
            Request req0 = new Request(null, null);
            HttpSession session = contextHandler.getSessionHandler().newHttpSession(req0); //pretend request created session
            String id = session.getId();
            req0.onCompleted(); //pretend request exited

            assertTrue(contextHandler.getSessionHandler().getSessionCache().contains(id));

            //Make a fake request that does not exit the session
            Request req1 = new Request(null, null);
            HttpSession s1 = contextHandler.getSessionHandler().getHttpSession(id);
            assertNotNull(s1);
            assertSame(session, s1);
            req1.enterSession(s1);
            req1.setSessionHandler(contextHandler.getSessionHandler());
            assertTrue(contextHandler.getSessionHandler().getSessionCache().contains(id));
            assertEquals(1, ((Session)session).getRequests());

            //Make another fake request that does not exit the session
            Request req2 = new Request(null, null);
            HttpSession s2 = contextHandler.getSessionHandler().getHttpSession(id);
            assertNotNull(s2);
            assertSame(session, s2);
            req2.enterSession(s2);
            req2.setSessionHandler(contextHandler.getSessionHandler());
            assertEquals(2, ((Session)session).getRequests());

            //Renew the session id
            Request req3 = new Request(null, null);
            final HttpSession s3 = contextHandler.getSessionHandler().getHttpSession(id);
            assertNotNull(s3);
            assertSame(session, s3);
            req3.enterSession(s3);
            req3.setSessionHandler(contextHandler.getSessionHandler());

            //Invalidate the session
            Request req4 = new Request(null, null);
            final HttpSession s4 = contextHandler.getSessionHandler().getHttpSession(id);
            assertNotNull(s4);
            assertSame(session, s4);
            req4.enterSession(s4);
            req4.setSessionHandler(contextHandler.getSessionHandler());

            Thread renewThread = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    //simulate req3 calling Request.changeSessionId
                    String oldid = ((Session)s3).getId(); //old id

                    //Session may already be invalid depending on timing
                    try
                    {
                        ((Session)s3).renewId(req3);
                        //After this call, the session must have changed id, and it may also be
                        //invalid, depending on timing.
                        assertFalse(oldid.equals(((Session)s3).getId()));
                    }
                    catch (IllegalStateException e)
                    {
                        //the session was invalid before we called renewId
                    }
                    catch (Throwable e)
                    {
                        //anything else is a failure
                        fail(e);
                    }
                }
            }
                );

            Thread invalidateThread = new Thread(() ->
            {
                //simulate req4 doing an invalidate that we hope overlaps with req3 renewId
                try
                {
                    Random random = new Random();
                    if ((random.nextInt(10) % 2)  == 0)
                        Thread.currentThread().sleep(2); //small sleep to try and make timing more random
                    ((Session)s4).invalidate();
                    assertFalse(((Session)s4).isValid());
                }
                catch (InterruptedException e)
                {
                    // no op
                }
            }
            );

            invalidateThread.start();
            renewThread.start();
            renewThread.join();
            invalidateThread.join();

        }
        finally
        {
            server.stop();
        }
    }

    /**
     * Test that a session id can be renewed.
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

        TestSessionDataStore store = new TestSessionDataStore(true); //fake passivation
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);

        context.start();

        //put a session in the cache and store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        cache.add("1234", session);
        assertTrue(cache.contains("1234"));

        cache.renewSessionId("1234", "5678", "1234.foo", "5678.foo");

        assertTrue(cache.contains("5678"));
        assertFalse(cache.contains("1234"));

        assertTrue(store.exists("5678"));
        assertFalse(store.exists("1234"));
    }

    /**
     * Test that a session that is in the cache can be retrieved.
     */
    @Test
    public void testGetSessionInCache()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);

        //ensure the session is in the cache
        cache.add("1234", session);

        //peek into the cache and see if it is there
        assertTrue(((AbstractSessionCache)cache).contains("1234"));
    }

    @Test
    public void testAdd()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //add data for a session to the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        
        //create a session for the existing session data, add it to the cache
        Session session = cache.newSession(data);
        cache.add("1234", session);

        assertEquals(1, session.getRequests());
        assertTrue(session.isResident());
        assertTrue(cache.contains("1234"));
        assertFalse(store.exists("1234"));
    }

    @Test
    public void testRelease()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //create data for a session in the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        
        //make a session for the existing id, add to cache
        Session session = cache.newSession(data);
        cache.add("1234", session);
        
        //release use of newly added session
        cache.release("1234", session);

        assertEquals(0, session.getRequests());
        assertTrue(session.isResident());
        assertTrue(cache.contains("1234"));
        assertTrue(store.exists("1234"));
    }

    /**
     * Test contains method.
     */
    @Test
    public void testContains()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = (SessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //test one that isn't contained
        assertFalse(cache.contains("1234"));

        //test one that is contained
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        cache.add("1234", session);
        assertTrue(cache.contains("1234"));
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
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(data);
        cache.checkInactiveSession(session);
        assertFalse(store.exists("1234"));
        assertFalse(cache.contains("1234"));
        assertFalse(session.isResident());
        //ie nothing happens to the session

        //test session that is resident but not valid
        cache.add("1234", session);
        cache.release("1234", session); //this will write session
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
        data.setAccessed(now - TimeUnit.SECONDS.toMillis(30));
        cache.checkInactiveSession(session);
        assertFalse(cache.contains("1234"));
        assertFalse(session.isResident());

        //test  EVICT_ON_SESSION_EXIT with requests still active.
        //this should not affect the session because it this is an idle test only
        SessionData data2 = store.newSessionData("567", now, now - TimeUnit.SECONDS.toMillis(30), now - TimeUnit.SECONDS.toMillis(40), TimeUnit.MINUTES.toMillis(10));
        data2.setExpiry(now + TimeUnit.DAYS.toMillis(1)); //not expired
        Session session2 = cache.newSession(data2);
        cache.add("567", session2); //ensure session is in cache
        cache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        session2.access(System.currentTimeMillis()); //simulate 1 request in session
        assertTrue(cache.contains("567"));
        cache.checkInactiveSession(session2);
        assertTrue(cache.contains("567")); //not evicted

        //test  EVICT_ON_SESSION_EXIT - requests not active
        //this should not affect the session because this is an idle test only
        session2.complete(); //NOTE:don't call cache.release as this will remove the session
        cache.checkInactiveSession(session2);
        assertTrue(cache.contains("567"));
    }

    @Test
    public void testSaveOnEviction()
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
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(data);
        cache.add("1234", session); //make it resident
        assertTrue(cache.contains("1234"));
        long accessed = now - TimeUnit.SECONDS.toMillis(30); //make it idle
        data.setAccessed(accessed);
        cache.release("1234", session);
        assertTrue(cache.contains("1234"));
        assertTrue(session.isResident());
        cache.checkInactiveSession(session);
        assertFalse(cache.contains("1234"));
        assertFalse(session.isResident());
        SessionData retrieved = store.load("1234");
        assertEquals(accessed, retrieved.getAccessed()); //check that we persisted the session before we evicted
    }
}
