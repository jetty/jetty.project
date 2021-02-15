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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  Base class for all tests on all flavours of SessionCache
 *
 */
public abstract class AbstractSessionCacheTest
{
    public static class UnreadableSessionDataStore extends AbstractSessionDataStore
    {
        int _count;
        int _calls;
        SessionData _data;

        public UnreadableSessionDataStore(int count, SessionData data)
        {
            _count = count;
            _data = data;
        }

        @Override
        public boolean isPassivating()
        {
            return false;
        }

        @Override
        public boolean exists(String id) throws Exception
        {
            return _data != null;
        }

        @Override
        public boolean delete(String id) throws Exception
        {
            _data = null;
            return true;
        }

        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
        }

        @Override
        public SessionData doLoad(String id) throws Exception
        {
            ++_calls;
            if (_calls <= _count)
                throw new UnreadableSessionDataException(id, _context, new IllegalStateException("Throw for test"));
            else
                return _data;
        }

        @Override
        public Set<String> doGetExpired(Set<String> candidates)
        {
            return null;
        }
    }

    public static class TestSessionActivationListener implements HttpSessionActivationListener
    {
        public int passivateCalls = 0;
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

    public abstract AbstractSessionCacheFactory newSessionCacheFactory(int evictionPolicy,
                                                                       boolean saveOnCreate, 
                                                                       boolean saveOnInactiveEvict,
                                                                       boolean removeUnloadableSessions,
                                                                       boolean flushOnResponseCommit);

    public abstract void checkSessionBeforeShutdown(String id,
                                                    SessionDataStore store, 
                                                    SessionCache cache, 
                                                    TestSessionActivationListener activationListener,
                                                    TestHttpSessionListener sessionListener) throws Exception;
    
    public abstract void checkSessionAfterShutdown(String id,
                                                   SessionDataStore store,
                                                   SessionCache cache,
                                                   TestSessionActivationListener activationListener,
                                                   TestHttpSessionListener sessionListener) throws Exception;

    /**
     * Test that a session that exists in the datastore, but that cannot be
     * read will be invalidated and deleted, and thus a request to re-use that
     * same id will not succeed.
     * 
     * @throws Exception
     */
    @Test
    public void testUnreadableSession() throws Exception
    {
        Server server = new Server();
        server.setSessionIdManager(new DefaultSessionIdManager(server));

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);
        server.setHandler(context);

        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        //prefill the datastore with a session that will be treated as unreadable
        UnreadableSessionDataStore store = new UnreadableSessionDataStore(1, new SessionData("1234", "/test", "0.0.0.0", System.currentTimeMillis(), 0, 0, -1));
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        server.start();

        try (StacklessLogging ignored = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            //check that session 1234 cannot be read, ie returns null AND
            //that it is deleted in the datastore
            Session session = context.getSessionHandler().getSession("1234");
            assertNull(session);
            assertFalse(store.exists("1234"));

            //now try to make a session with the same id as if from a request with
            //a SESSION_ID cookie set - the id from the cookie should not be able to
            //be re-used because we just deleted the session with that id. Ids cannot
            //be re-used (unless another context is already using that same id (ie cross
            //context dispatch), which is not the case in this test).
            Request request = new Request(null, null);
            request.setRequestedSessionId("1234");
            HttpSession newSession = context.getSessionHandler().newHttpSession(request);
            assertNotEquals("1234", newSession.getId());
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * Test that a new Session object can be created from
     * previously persisted data (SessionData).
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

        TestSessionDataStore store = new TestSessionDataStore(true); //fake passivation
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);

        context.start();

        long now = System.currentTimeMillis();
        //fake persisted data
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        assertNotNull(session);
        assertEquals("1234", session.getId());
    }
    
    
    /**
     * Test that the cache can load from the SessionDataStore
     */
    @Test
    public void testGetSessionNotInCache()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //put session data into the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        store.store("1234", data);

        assertFalse(cache.contains("1234"));

        Session session = cache.get("1234");
        assertEquals(1, session.getRequests());
        assertNotNull(session);
        assertEquals("1234", session.getId());
        assertEquals(now - 20, session.getCreationTime());
    }
    
    /**
     * Test state of session with call to commit
     * 
     * @throws Exception
     */
    @Test
    public void testCommit() throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        //flushOnResponseCommit is true
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, true);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();
        
        //Mimic various states of a session when a response is about
        //to be committed:
        
        //call commit: session has not changed, should not be written
        store._numSaves.set(0); //clear save counter
        Session session = createUnExpiredSession(cache, store, "1234");
        cache.add("1234", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);
        
        //call commit: session has changed, should be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "456");
        cache.add("456", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved
        session.setAttribute("foo", "bar");
        commitAndCheckSaveState(cache, store, session, true, true, false, false, 0, 1);
        
        //call commit: only the metadata has changed will not be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "678");
        cache.add("678", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved
        session.getSessionData().calcAndSetExpiry(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);

        //Test again with a savePeriod set - as savePeriod only
        //affects saving when the session is not dirty, the savePeriod
        //should not affect whether or not the session is saved on call
        //to commit
        store.setSavePeriodSec(60);

        //call commit: session has not changed, should not be written anyway
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "890");
        cache.add("890", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);
        
        //call commit: session has changed so session must be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "012");
        cache.add("012", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved
        session.setAttribute("foo", "bar");
        commitAndCheckSaveState(cache, store, session, true, true, false, false, 0, 1);

        //call commit: only the metadata has changed will not be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "234");
        session.getSessionData().setMetaDataDirty(true);
        cache.add("234", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved
        session.getSessionData().calcAndSetExpiry(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);
    }
    
    /**
     * Test what happens with various states of a session when commit
     * is called before release
     * @throws Exception
     */
    @Test
    public void testCommitAndRelease() throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        //flushOnResponseCommit is true
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, true);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();
        
        //Mimic various states of a session when a response is about
        //to be committed:
        
        //call commit: session has not changed, should not be written
        Session session = createUnExpiredSession(cache, store, "1234");
        cache.add("1234", session);
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);        
        //call release: session has not changed, but metadata has, should be written
        cache.release("1234", session);
        assertEquals(1, store._numSaves.get());
        assertFalse(session.getSessionData().isDirty());
        assertFalse(session.getSessionData().isMetaDataDirty());

        //call commit: session has changed, should be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "456");
        cache.add("456", session);
        session.setAttribute("foo", "bar");
        session.getSessionData().setLastSaved(100); //simulate not "new" session, ie has been previously saved
        commitAndCheckSaveState(cache, store, session, true, true, false, false, 0, 1);
        //call release: session not dirty but release changes metadata, so it will be saved
        cache.release("456", session);
        assertEquals(2, store._numSaves.get());
        assertFalse(session.getSessionData().isDirty());
        assertFalse(session.getSessionData().isMetaDataDirty());

        //call commit: only the metadata has changed will not be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "678");
        session.getSessionData().calcAndSetExpiry(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
        session.getSessionData().setLastSaved(100); //simulate session not being "new", ie never previously saved
        cache.add("678", session);
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);
        //call release: the metadata is dirty session should be written
        cache.release("678", session);
        assertEquals(1, store._numSaves.get());
        assertFalse(session.getSessionData().isDirty());
        assertFalse(session.getSessionData().isMetaDataDirty());
        
        //Test again with a savePeriod set - only save if time last saved exceeds 60sec
        store.setSavePeriodSec(60);

        //call commit: session has not changed, should not be written anyway
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "890");
        cache.add("890", session);
        session.getSessionData().setLastSaved(100); //simulate last save long time ago
        session.getSessionData().setMetaDataDirty(false);
        commitAndCheckSaveState(cache, store, session, false, false, false, false, 0, 0);
        //call release: not dirty but release sets metadata true, plus save period exceeded so write
        cache.release("1234", session);
        assertEquals(1, store._numSaves.get());
        assertFalse(session.getSessionData().isDirty());
        assertFalse(session.getSessionData().isMetaDataDirty());
        
        //call commit: session has changed so session must be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "012");
        cache.add("012", session);
        session.getSessionData().setLastSaved(100); //simulate previously saved session
        session.setAttribute("foo", "bar");
        session.getSessionData().setMetaDataDirty(false);
        commitAndCheckSaveState(cache, store, session, true, false, false, false, 0, 1);
        //call release: not dirty, release sets metadirty true (recalc expiry) but previous save too recent to exceed save period --> no write
        cache.release("012", session);
        assertEquals(1, store._numSaves.get());
        assertFalse(session.getSessionData().isDirty());
        assertTrue(session.getSessionData().isMetaDataDirty());

        //call commit: only the metadata has changed will not be written
        store._numSaves.set(0); //clear save counter
        session = createUnExpiredSession(cache, store, "234");
        session.getSessionData().calcAndSetExpiry(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1));
        session.getSessionData().setLastSaved(System.currentTimeMillis()); //simulate session last saved recently
        commitAndCheckSaveState(cache, store, session, false, true, false, true, 0, 0);
        //call release: not dirty, release sets metadirty true (recalc expiry) but not within saveperiod so skip write
        cache.release("1234", session);
        assertEquals(0, store._numSaves.get());
        assertFalse(session.getSessionData().isDirty());
        assertTrue(session.getSessionData().isMetaDataDirty());
    }

    /**
     * Test the exist method.
     */
    @Test
    public void testExists()
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

        //test one that doesn't exist at all
        assertFalse(cache.exists("1234"));

        //test one that only exists in the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        store.store("1234", data);
        assertTrue(cache.exists("1234"));

        //test one that exists in the cache also
        Session session = cache.newSession(data);
        cache.add("1234", session);
        assertTrue(cache.exists("1234"));
    }
    
    /**
     * Test the delete method.
     */
    @Test
    public void testDelete()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, true, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //test remove non-existent session
        Session session = cache.delete("1234");
        assertNull(session);

        //test remove of existing session in store only
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        store.store("1234", data);
        session = cache.delete("1234");
        assertNotNull(session);
        assertFalse(store.exists("1234"));
        assertFalse(cache.contains("1234"));

        //test remove of session in both store and cache
        session = cache.newSession(null, "1234", now - 20, TimeUnit.MINUTES.toMillis(10)); //saveOnCreate ensures write to store
        cache.add("1234", session);
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

        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //test no candidates, no data in store
        Set<String> result = cache.checkExpiration(Collections.emptySet());
        assertTrue(result.isEmpty());

        //test candidates that are in the cache and NOT expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(data);
        cache.add("1234", session);
        cache.release("1234", session);
        assertTrue(cache.exists("1234"));
        result = cache.checkExpiration(Collections.singleton("1234"));
        assertTrue(result.isEmpty());

        //test candidates that are in the cache AND expired
        data.setExpiry(1);
        result = cache.checkExpiration(Collections.singleton("1234"));
        assertEquals(1, result.size());
        assertEquals("1234", result.iterator().next());

        //test candidates that are not in the cache
        SessionData data2 = store.newSessionData("567", now - 50, now - 40, now - 30, TimeUnit.MINUTES.toMillis(10));
        data2.setExpiry(1);
        store.store("567", data2);

        result = cache.checkExpiration(Collections.emptySet());
        assertThat(result, containsInAnyOrder("1234", "567"));
    }

    @Test
    public void testSaveOnCreateTrue()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, true, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        long now = System.currentTimeMillis();
        cache.newSession(null, "1234", now, TimeUnit.MINUTES.toMillis(10));
        assertTrue(store.exists("1234"));
    }

    @Test
    public void testSaveOnCreateFalse()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        long now = System.currentTimeMillis();
        cache.newSession(null, "1234", now, TimeUnit.MINUTES.toMillis(10));
        assertFalse(store.exists("1234"));
    }
    
    /**
     * Test shutting down the server with invalidateOnShutdown==false
     * 
     * @throws Exception
     */
    @Test
    public void testNoInvalidateOnShutdown()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);
        server.setHandler(context);

        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore(true); //fake passivation
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        TestHttpSessionListener sessionListener = new TestHttpSessionListener();
        context.getSessionHandler().addEventListener(sessionListener);

        server.start();

        //put a session in the cache and store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        TestSessionActivationListener activationListener = new TestSessionActivationListener();
        cache.add("1234", session);
        session.setAttribute("aaa", activationListener);
        cache.release("1234", session);
        checkSessionBeforeShutdown("1234", store, cache, activationListener, sessionListener);

        server.stop(); //calls shutdown

        checkSessionAfterShutdown("1234", store, cache, activationListener, sessionListener);
    }
    
    /**
     * Test shutdown of the server with invalidateOnShutdown==true
     * @throws Exception
     */
    @Test
    public void testInvalidateOnShutdown()
        throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        server.setHandler(context);

        //flushOnResponseCommit is true
        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, true);
        cacheFactory.setInvalidateOnShutdown(true);
        SessionCache cache = cacheFactory.getSessionCache(context.getSessionHandler());


        TestSessionDataStore store = new TestSessionDataStore(true); //fake a passivating store
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        TestHttpSessionListener sessionListener = new TestHttpSessionListener();
        context.getSessionHandler().addEventListener(sessionListener);

        server.start();

        //Make a session in the store and cache and check that it is invalidated on shutdown
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("8888", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        cache.add("8888", session);

        TestSessionActivationListener activationListener = new TestSessionActivationListener();
        session.setAttribute("aaa", activationListener);
        cache.release("8888", session);
        checkSessionBeforeShutdown("8888", store, cache, activationListener, sessionListener);

        server.stop();

        checkSessionAfterShutdown("8888", store, cache, activationListener, sessionListener);
    }

    public void commitAndCheckSaveState(SessionCache cache, TestSessionDataStore store, Session session, 
                                        boolean expectedBeforeDirty, boolean expectedBeforeMetaDirty, 
                                        boolean expectedAfterDirty, boolean expectedAfterMetaDirty,
                                        int expectedBeforeNumSaves, int expectedAfterNumSaves)
                                            throws Exception
    {
        assertEquals(expectedBeforeDirty, session.getSessionData().isDirty());
        assertEquals(expectedBeforeMetaDirty, session.getSessionData().isMetaDataDirty());
        assertEquals(expectedBeforeNumSaves, store._numSaves.get());
        cache.commit(session);
        assertEquals(expectedAfterDirty, session.getSessionData().isDirty());
        assertEquals(expectedAfterMetaDirty, session.getSessionData().isMetaDataDirty());
        assertEquals(expectedAfterNumSaves, store._numSaves.get());
    }

    public Session createUnExpiredSession(SessionCache cache, SessionDataStore store, String id)
    {
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData(id, now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        return cache.newSession(data);
    }
}
