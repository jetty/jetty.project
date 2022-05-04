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

package org.eclipse.jetty.session;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class AbstractSessionManagerTest
{
    @Test
    public void testGetSessionCookie() throws Exception
    {
        //Make a session
        SessionData sessionData = new SessionData("1234", "_test", "0.0.0.0", 100, 200, 200, -1);
        TestableSessionManager sessionManager = new TestableSessionManager();
        Session session = new Session(sessionManager, sessionData);
        session.setExtendedId("1234.foo");
        session.getSessionData().setLastNode("foo");

        //check cookie with all default cookie config settings
        HttpCookie cookie = sessionManager.getSessionCookie(session, "/test", false);
        assertNotNull(cookie);
        assertEquals(SessionManager.__DefaultSessionCookie, cookie.getName());
        assertEquals(SessionManager.__DefaultSessionDomain, cookie.getDomain());
        assertEquals("/test", cookie.getPath());
        assertFalse(cookie.isSecure());
        assertFalse(cookie.isHttpOnly());
        
        //check cookie with httpOnly and secure
        sessionManager.setHttpOnly(true);
        sessionManager.setSecureRequestOnly(true);
        sessionManager.setSecureCookies(true);
        cookie = sessionManager.getSessionCookie(session, "/test", true);
        assertNotNull(cookie);
        assertEquals(SessionManager.__DefaultSessionCookie, cookie.getName());
        assertEquals(SessionManager.__DefaultSessionDomain, cookie.getDomain());
        assertEquals("/test", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        
        //check cookie when cookie config is set
        sessionManager.getCookieConfig().put(SessionManager.__SessionCookieProperty, "MYSESSIONID");
        sessionManager.getCookieConfig().put(SessionManager.__SessionDomainProperty, "foo.bar");
        sessionManager.getCookieConfig().put(SessionManager.__SessionPathProperty, "/special");
        sessionManager.configureCookies();
        cookie = sessionManager.getSessionCookie(session, "/test", false);
        assertNotNull(cookie);
        assertEquals("MYSESSIONID", cookie.getName());
        assertEquals("foo.bar", cookie.getDomain());
        assertEquals("/special", cookie.getPath());
        assertTrue(cookie.isSecure());
        assertTrue(cookie.isHttpOnly());
    }
    
    @Test
    public void testAccess() throws Exception
    {
        //Make a session
        SessionData sessionData = new SessionData("1234", "_test", "0.0.0.0", 100, 200, 200, -1);
        TestableSessionManager sessionManager = new TestableSessionManager();
        Session session = new Session(sessionManager, sessionData);
        session.setExtendedId("1234.foo");
        session.getSessionData().setLastNode("foo");
        session.setResident(true); //pretend its in a cache
        
        //not using cookies
        sessionManager.setUsingCookies(false);
        HttpCookie cookie = sessionManager.access(session, false);
        assertNull(cookie);
        
        //session cookies never expire, shouldn't create a new one
        sessionManager.setUsingCookies(true);
        session.getSessionData().setCookieSet(0);
        cookie = sessionManager.access(session, false);
        assertNull(cookie);
        
        //session cookies expire, should create a new one
        session.getSessionData().setCookieSet(300); //time session cookie was set
        sessionManager.setRefreshCookieAge(10); //cookie reset after 10sec
        sessionManager.setMaxCookieAge(5); //cookies cannot be older than 5 sec
        cookie = sessionManager.access(session, false);
        assertNotNull(cookie);
    }

    @Test
    public void testCalculateInactivityTimeOut() throws Exception
    {
        //Make a session
        TestableSessionManager sessionManager = new TestableSessionManager();

        AbstractSessionCache sessionCache = new AbstractSessionCache(sessionManager)
        {
            @Override
            public void shutdown()
            {
            }

            @Override
            public Session newSession(SessionData data)
            {
                return null;
            }

            @Override
            protected Session doGet(String id)
            {
                return null;
            }

            @Override
            protected Session doPutIfAbsent(String id, Session session)
            {
                return null;
            }

            @Override
            protected Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction)
            {
                return null;
            }

            @Override
            protected boolean doReplace(String id, Session oldValue, Session newValue)
            {
                return false;
            }

            @Override
            public Session doDelete(String id)
            {
                return null;
            }
        };
        sessionManager.setSessionCache(sessionCache);
        
        //calculate inactivity time when sessions are never evicted && they are immortal
        sessionCache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        long timeToExpiry = 0;
        long maxIdleTime = -1;
        long timeout = sessionManager.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(-1, timeout); //no timeout
        
        //calculate inactivity time when sessions are evicted && they are immortal
        int evictionTimeout = 1; //period after which session should be evicted
        sessionCache.setEvictionPolicy(evictionTimeout);
        timeout = sessionManager.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(TimeUnit.SECONDS.toMillis(evictionTimeout), timeout); //inactivity timeout == the eviction timeout
        
        //calculate inactivity time when sessions are never evicted && they are mortal
        sessionCache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        timeToExpiry = 1000; //session has 1sec remaining before expiry
        maxIdleTime = 20000; //sessions only last 20sec
        timeout = sessionManager.calculateInactivityTimeout("1234", timeToExpiry, 20000);
        assertEquals(timeToExpiry, timeout);

        //calculate inactivity time when sessions are evicted && they are mortal
        evictionTimeout = 5; //will be evicted after 5sec inactivity
        sessionCache.setEvictionPolicy(evictionTimeout);
        timeout = sessionManager.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(TimeUnit.SECONDS.toMillis(evictionTimeout), timeout); //the eviction timeout is smaller than the maxIdle timeout

        //calculate inactivity time when sessions are evicted && they are mortal, session expired
        timeToExpiry = 0;
        timeout = sessionManager.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(0, timeout); //no timeout for an expired session
      
        //calculate inactivity time when sessions are evicted && they are mortal, session expired
        maxIdleTime = 20000; //sessions only last 20sec
        evictionTimeout = 30; //idle eviction timeout is 30sec
        timeToExpiry = 1000; //session not yet expired
        sessionCache.setEvictionPolicy(evictionTimeout);
        timeout = sessionManager.calculateInactivityTimeout("1234", timeToExpiry, maxIdleTime);
        assertEquals(maxIdleTime, timeout); //the maxIdleTime is smaller than the eviction timeout
    }
    
    /**
     * Test that an immortal session is never scavenged, regardless of whether it
     * is evicted from the cache or not.
     * 
     * @throws Exception
     */
    @Test
    public void testImmortalScavenge() throws Exception
    {
        int scavengeSec = 1;
        int maxInactiveSec = -1;
        
        Server server = new Server();
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        server.addBean(idManager, true);
        HouseKeeper housekeeper = new HouseKeeper();
        housekeeper.setIntervalSec(scavengeSec);
        scavengeSec = (int)housekeeper.getIntervalSec(); //housekeeper puts some jitter in the interval if it is short
        housekeeper.setSessionIdManager(idManager);
        idManager.setSessionHouseKeeper(housekeeper);
        
        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setMaxInactiveInterval(maxInactiveSec);
        DefaultSessionCache sessionCache = new DefaultSessionCache(sessionManager);
        sessionCache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        TestableSessionDataStore sessionDataStore = new TestableSessionDataStore();
        sessionCache.setSessionDataStore(sessionDataStore);
        sessionManager.setSessionCache(sessionCache);

        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();
        
        //check with never evict
        long waitMs = scavengeSec * 2500; //wait for at least 1 run of the scavenger
        checkScavenge(true, waitMs, sessionManager);
        
        server.stop();
        
        //Check with eviction on exit
        sessionCache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        server.start();
        checkScavenge(true, waitMs, sessionManager);
        
        //Check with evict on idle
        server.stop();
        scavengeSec = scavengeSec * 2;
        waitMs = scavengeSec * 2500; //wait for at least 1 run of the scavenger
        housekeeper.setIntervalSec(scavengeSec); //make scavenge cycles longer
        sessionCache.setEvictionPolicy(scavengeSec / 2); //make idle shorter than scavenge
        server.start();
        checkScavenge(true, waitMs, sessionManager);
    }
    
    /**
     * Test that a session that has a max valid time is always scavenged,
     * regardless of whether it is evicted from the cache or not.
     * @throws Exception
     */
    @Test
    public void testNonImmortalScavenge() throws Exception
    {
        int scavengeSec = 1;
        int maxInactiveSec = 3;
        
        Server server = new Server();
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        server.addBean(idManager, true);
        HouseKeeper housekeeper = new HouseKeeper();
        housekeeper.setIntervalSec(scavengeSec);
        scavengeSec = (int)housekeeper.getIntervalSec(); //housekeeper puts some jitter in the interval if it is short
        housekeeper.setSessionIdManager(idManager);
        idManager.setSessionHouseKeeper(housekeeper);
        
        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setMaxInactiveInterval(maxInactiveSec);
        DefaultSessionCache sessionCache = new DefaultSessionCache(sessionManager);
        sessionCache.setEvictionPolicy(SessionCache.NEVER_EVICT);
        TestableSessionDataStore sessionDataStore = new TestableSessionDataStore();
        sessionCache.setSessionDataStore(sessionDataStore);
        sessionManager.setSessionCache(sessionCache);

        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();
        
        //check the session should be scavenged after the max valid of the session has passed
        long waitMs = (maxInactiveSec + scavengeSec) * 1000;
        checkScavenge(false, waitMs, sessionManager);
        
        server.stop();
        
        //now change to EVICT_ON_EXIT and check that the session is still scavenged
        sessionCache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        server.start();
        checkScavenge(false, waitMs, sessionManager);
        
        server.stop();
        
        //now change to evict after idle and check that the session is still scavenged
        sessionCache.setEvictionPolicy(scavengeSec * 2);
        server.start();
        checkScavenge(false, waitMs, sessionManager);
    }

    private void checkScavenge(boolean expectExist, long waitMs, TestableSessionManager sessionManager)
        throws Exception
    {
        int evictionPolicy = sessionManager.getSessionCache().getEvictionPolicy();

        TestableSessionConsumer consumer = new TestableSessionConsumer();
        sessionManager.newSession(null, "1234", consumer);
        Session session = consumer.getSession();
        String id = session.getId();
        sessionManager.commit(session);
        sessionManager.complete(session); //exit the session
        if (evictionPolicy != SessionCache.EVICT_ON_SESSION_EXIT)
            assertTrue(sessionManager.getSessionCache().contains(id));
        assertTrue(sessionManager.getSessionCache().getSessionDataStore().exists(id));
        
        //if there is an idle eviction timeout, wait until it is passed
        if (evictionPolicy >= SessionCache.EVICT_ON_INACTIVITY)
        {
            Thread.sleep(evictionPolicy * 1500L);
            //check it has been evicted from the cache
            assertFalse(sessionManager.getSessionCache().contains(id));
        }
        
        //Wait some time. This must be at least one run of the scavenger, more
        //if the session has a max valid time.
        Thread.sleep(waitMs);

        //test the session
        session = sessionManager.getSession(id);
        if (expectExist)
        {
            assertNotNull(session);
            assertTrue(session.isValid());
            assertTrue(sessionManager.getSessionCache().getSessionDataStore().exists(id));
        }
        else
        {
            assertNull(session);
            assertFalse(sessionManager.getSessionCache().getSessionDataStore().exists(id));
        }
    }
}
