//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Session;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DefaultSessionCacheTest
 */
public class DefaultSessionCacheTest extends AbstractSessionCacheTest
{
    private class FailableSessionDataStore extends AbstractSessionDataStore
    {
        public boolean[] _nextStoreResult;
        public int i = 0;

        public FailableSessionDataStore(boolean[] results)
        {
            _nextStoreResult = results;
        }
        
        @Override
        public boolean isPassivating()
        {
            return false;
        }

        @Override
        public boolean doExists(String id) throws Exception
        {
            return true;
        }

        @Override
        public SessionData doLoad(String id) throws Exception
        {
            return null;
        }

        @Override
        public boolean delete(String id) throws Exception
        {
            return false;
        }

        @Override
        public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
        {
            if (_nextStoreResult != null && i < _nextStoreResult.length && !_nextStoreResult[i++])
            {
                throw new IllegalStateException("Testing store");
            }
        }

        @Override
        public Set<String> doCheckExpired(Set<String> candidates, long timeLimit)
        {
            return candidates;
        }

        @Override
        public Set<String> doGetExpired(long timeLimit)
        {
            return Collections.emptySet();
        }

        @Override
        public void doCleanOrphans(long timeLimit)
        {
        }
    }
    
    @Override
    public AbstractSessionCacheFactory newSessionCacheFactory(int evictionPolicy, boolean saveOnCreate,
                                                              boolean saveOnInactiveEvict, boolean removeUnloadableSessions,
                                                              boolean flushOnResponseCommit)
    {
        DefaultSessionCacheFactory factory = new DefaultSessionCacheFactory();
        factory.setEvictionPolicy(evictionPolicy);
        factory.setSaveOnCreate(saveOnCreate);
        factory.setSaveOnInactiveEviction(saveOnInactiveEvict);
        factory.setRemoveUnloadableSessions(removeUnloadableSessions);
        factory.setFlushOnResponseCommit(flushOnResponseCommit);
        return factory;
    }
    
    @Override
    public void checkSessionBeforeShutdown(String id,
                                           TestableSessionDataStore store,
                                           SessionCache cache,
                                           TestableSessionManager sessionManager) throws Exception
    {
        assertTrue(store.exists(id));
        assertTrue(cache.contains(id));
        assertFalse(sessionManager._sessionDestroyedListenersCalled.contains(id));
        assertTrue(sessionManager._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionManager._sessionActivationListenersCalled.contains(id));
    }
    
    @Override
    public void checkSessionAfterShutdown(String id,
                                          TestableSessionDataStore store,
                                          SessionCache cache,
                                          TestableSessionManager sessionManager) throws Exception
    {
        if (cache.isInvalidateOnShutdown())
        {
            //should have been invalidated and removed
            assertFalse(store.exists(id));
            assertFalse(cache.contains(id));
            assertTrue(sessionManager._sessionDestroyedListenersCalled.contains(id));
        }
        else
        {
            //Session should still exist, but not be in the cache
            assertTrue(store.exists(id));
            assertFalse(cache.contains(id));
            long passivateCount = sessionManager._sessionPassivationListenersCalled.stream().filter(s -> s.equals(id)).count();
            long activateCount = sessionManager._sessionActivationListenersCalled.stream().filter(s -> s.equals(id)).count();
            assertEquals(2, passivateCount);
            assertEquals(1, activateCount); //no re-activate on shutdown
        }
    }

    /**
     * Test renewing a session id that is also being invalidated
     *
     */
    @Test
    public void testRenewWithInvalidate() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        server.addBean(idManager, true);

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore(true); //fake passivation
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        //put a session in the cache and store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        ManagedSession session = cache.newSession(data);
        cache.add("1234", session);
        assertTrue(cache.contains("1234"));
        assertEquals(1, session.getRequests());
        
        //decrement request count
        cache.release(session);
        assertEquals(0, session.getRequests());
        
        //increment request count of session
        Session s1 = cache.getAndEnter("1234", true);
        assertEquals(1, session.getRequests());
        
        //increment request count of session
        Session s2 = cache.getAndEnter("1234", true);
        assertEquals(2, session.getRequests());

        Thread renewThread = new Thread(() ->
        {
            //simulate calling Request.changeSessionId
            String oldid = s1.getId(); //old id
            assertEquals("1234", oldid);

            //Session may already be invalid depending on timing
            try
            {                    
                s1.renewId(new TestableRequest(), null);
                //After this call, the session must have changed id, and it may also be
                //invalid, depending on timing.
                assertFalse(oldid.equals(s1.getId()));
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
        );
        
        Thread invalidateThread = new Thread(() ->
        {
            //simulate a Request calling invalidate that we hope overlaps with the renewId
            try
            {
                Random random = new Random();
                if ((random.nextInt(10) % 2)  == 0)
                    Thread.currentThread().sleep(2); //small sleep to try and make timing more random
                s2.invalidate();
                assertFalse(s2.isValid());
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
        
        //as the Session object is shared between requests by the DefaultSessionCache,
        //all variables should be the same
        assertEquals(s1.getId(), s2.getId());
        assertEquals(session.getId(), s1.getId());
    }

    /**
     * Test that a session id can be renewed.
     */
    @Test
    public void testRenewSessionId()
        throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore(true); //fake passivation
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        //put a session in the cache and store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        ManagedSession session = cache.newSession(data);
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

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        ManagedSession session = cache.newSession(data);

        //ensure the session is in the cache
        cache.add("1234", session);

        //peek into the cache and see if it is there
        assertTrue(((AbstractSessionCache)cache).contains("1234"));
    }

    /**
     * Test that the DefaultSessionCache shares the session object amongst
     * requests.
     * 
     * @throws Exception if there is an unspecified problem
     */
    @Test
    public void testSessionShared() throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();
       
        long now = System.currentTimeMillis();
        ManagedSession session = cache.newSession("1234", now, -1); //create an immortal session
        cache.add("1234", session); //add it to the cache
        
        //now fake another request coming in for the same session
        Session sessionB = cache.getAndEnter("1234", true);
        assertSame(session, sessionB);
        assertEquals(2, session.getRequests());
    }
    
    /**
     * Test adding a session to the cache
     * @throws Exception if there is an unspecified problem
     */
    @Test
    public void testAdd()
        throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        AbstractSessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        //add data for a session to the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        
        //create a session for the existing session data, add it to the cache
        ManagedSession session = cache.newSession(data);
        cache.add("1234", session);

        assertEquals(1, session.getRequests());
        try (AutoLock ignored = session.lock())
        {
            assertTrue(session.isResident());
        }
        assertTrue(cache.contains("1234"));
        assertFalse(store.exists("1234"));
    }

    /**
     * Test releasing use of a session 
     * @throws Exception if there is an unspecified problem
     */
    @Test
    public void testRelease()
        throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        //Test with NEVER_EVICT
        //create data for a session in the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        
        //make a session for the existing id, add to cache
        ManagedSession session = cache.newSession(data);
        cache.add("1234", session);
        
        //release use of newly added session
        cache.release(session);

        assertEquals(0, session.getRequests());
        try (AutoLock ignored = session.lock())
        {
            assertTrue(session.isResident());
        }
        assertTrue(cache.contains("1234"));
        assertTrue(store.exists("1234"));
        
        //Test EVICT_ON_SESSION_EXIT
        cache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        //increment request count
        cache.getAndEnter("1234", true);
        assertEquals(1, session.getRequests());
        //decrement request count
        cache.release(session);
        assertEquals(0, session.getRequests());
        try (AutoLock ignored = session.lock())
        {
            assertFalse(session.isResident());
        }
        assertFalse(cache.contains("1234"));
        assertTrue(store.exists("1234"));
    }

    @Test
    public void testRaceOnEviction() throws Exception
    {
        // Evict if session has not been accessed for 2 seconds or more.
        int evictionSeconds = 2;

        // Use EVICT_ON_INACTIVITY and saveOnInactiveEviction to force a save during eviction.
        Server server = new Server();
        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        SessionCacheFactory cacheFactory = newSessionCacheFactory(evictionSeconds, false, true, false, false);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        CyclicBarrier storeBarrier = new CyclicBarrier(2);
        TestableSessionDataStore store = new TestableSessionDataStore()
        {
            @Override
            public void doStore(String id, SessionData data, long lastSaveTime) throws Exception
            {
                // Wait on a barrier before proceeding with save to ensure request is inbound,
                // this happens under the session's lock; only await on the barrier when the
                // server is started as the latter stores the sessions during shutdown while
                // its state is STOPPING.
                if (server.getState().equals(STARTED))
                    storeBarrier.await(5, TimeUnit.SECONDS);
                super.doStore(id, data, lastSaveTime);
            }
        };

        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        // Make a session that is inactive.
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234",
            now - TimeUnit.MINUTES.toMillis(60),
            now - TimeUnit.MINUTES.toMillis(40),
            now - TimeUnit.MINUTES.toMillis(50),
            0);
        final ManagedSession session = cache.newSession(data);
        cache.add("1234", session); //increments usage count
        session.release(); //decrements the usage count

        try (AutoLock ignored = session.lock())
        {
            assertTrue(session.isResident());
        }
        assertTrue(session.isValid());
        assertTrue(session.isIdleLongerThan(evictionSeconds));

        // Make a thread to run the checkInactiveSession on it to evict it.
        Thread evictorThread = new Thread(() -> cache.checkInactiveSession(session), "evictorThread");
        evictorThread.start(); //start thread, will block on the barrier

        // Make another thread to call getAndEnter for the same session id.
        AtomicReference<Throwable> requestorExceptionRef = new AtomicReference<>();
        Thread requestorThread = new Thread(() ->
        {
            try
            {
                // Only proceed when the evictor has taken the session lock.
                await().atMost(5, TimeUnit.SECONDS).until(() -> storeBarrier.getNumberWaiting() == 1);

                // Verify we can get an evicted session back and it is in the cache.
                ManagedSession requestedSession = cache.getAndEnter("1234", true);
                assertNotNull(requestedSession);
                try (AutoLock ignored = requestedSession.lock())
                {
                    assertTrue(requestedSession.isResident());
                }
                // Check original session object is now not resident.
                try (AutoLock ignored = session.lock())
                {
                    assertFalse(session.isResident());
                }
            }
            catch (Throwable x)
            {
                requestorExceptionRef.set(x);
            }
        }, "requestorThread");
        requestorThread.start();

        // Wait until requestorThread is waiting to lock the session that is being evicted;
        // the requestorThread's state can be:
        //  - TIMED_WAITING (waiting in awaitility loop)
        //  - RUNNABLE (before or after the awaitility loop)
        //  - BLOCKED (waiting on the session compute lock in getAndEnter)
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread.State state = requestorThread.getState();
            return state == Thread.State.BLOCKED;
        });

        // Await on the barrier so the session datastore completes, releases the lock on the
        // session and the requestorThread can check it isn't resident, then do a reload
        // of the session data to create a new object.
        storeBarrier.await();

        // Wait for requestorThread to be done.
        requestorThread.join(5000);
        List.of(requestorThread.getStackTrace()).forEach(System.out::println);
        assertFalse(requestorThread.isAlive());
        assertNull(requestorExceptionRef.get());

        // Double check that the cache contains the session, although the pertinent check is
        // done in the requestorThread.
        assertTrue(cache.contains("1234"));

        // Cleanup.
        evictorThread.join(5000);
        assertFalse(evictorThread.isAlive());
        LifeCycle.stop(server);
    }

    /**
     * Test contains method.
     */
    @Test
    public void testContains()
        throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = (SessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();
        
        //test one that isn't contained
        assertFalse(cache.contains("1234"));

        //test one that is contained
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        ManagedSession session = cache.newSession(data);
        cache.add("1234", session);
        assertTrue(cache.contains("1234"));
    }

    /*
     * Test eviction settings with idle session
     */
    @Test
    public void testCheckInactiveSession()
        throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        //test NEVER EVICT
        //test session that is not resident
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        ManagedSession session = cache.newSession(data);
        cache.checkInactiveSession(session);
        assertFalse(store.exists("1234"));
        assertFalse(cache.contains("1234"));
        try (AutoLock ignored = session.lock())
        {
            assertFalse(session.isResident());
        }
        //ie nothing happens to the session

        //test session that is resident but not valid
        cache.add("1234", session);
        cache.release(session); //this will write session
        session._state = ManagedSession.State.INVALID;
        cache.checkInactiveSession(session);
        assertTrue(store.exists("1234"));
        assertTrue(cache.contains("1234"));
        try (AutoLock ignored = session.lock())
        {
            assertTrue(session.isResident());
        }
        assertFalse(session.isValid());
        //ie nothing happens to the session

        //test session that is resident, is valid, but NEVER_EVICT
        session._state = ManagedSession.State.VALID;
        cache.checkInactiveSession(session);
        assertTrue(store.exists("1234"));
        assertTrue(cache.contains("1234"));
        try (AutoLock ignored = session.lock())
        {
            assertTrue(session.isResident());
        }
        assertTrue(session.isValid());
        //ie nothing happens to the session

        //test EVICT_ON_INACTIVITY, session has passed the inactivity time
        cache.setEvictionPolicy(SessionCache.EVICT_ON_INACTIVITY);
        data.setAccessed(now - TimeUnit.SECONDS.toMillis(30));
        cache.checkInactiveSession(session);
        assertFalse(cache.contains("1234"));
        try (AutoLock ignored = session.lock())
        {
            assertFalse(session.isResident());
        }

        //test  EVICT_ON_SESSION_EXIT with requests still active.
        //this should not affect the session because it this is an idle test only
        SessionData data2 = store.newSessionData("567", now, now - TimeUnit.SECONDS.toMillis(30), now - TimeUnit.SECONDS.toMillis(40), TimeUnit.MINUTES.toMillis(10));
        data2.setExpiry(now + TimeUnit.DAYS.toMillis(1)); //not expired
        ManagedSession session2 = cache.newSession(data2);
        cache.add("567", session2); //ensure session is in cache
        cache.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        session2.access(System.currentTimeMillis()); //simulate 1 request in session
        assertTrue(cache.contains("567"));
        cache.checkInactiveSession(session2);
        assertTrue(cache.contains("567")); //not evicted

        //test  EVICT_ON_SESSION_EXIT - requests not active
        //this should not affect the session because this is an idle test only
        session2.release(); //NOTE:don't call cache.release as this will remove the session
        cache.checkInactiveSession(session2);
        assertTrue(cache.contains("567"));
    }

    /**
     * Test that if saveOnEviction==true, the session will be persisted before
     * it is evicted.
     * 
     * @throws Exception if there is an unspecified problem
     */
    @Test
    public void testSaveOnEviction()
        throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_INACTIVITY); //evict after 1 second inactivity
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();

        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        ManagedSession session = cache.newSession(data);
        cache.add("1234", session); //make it resident
        assertTrue(cache.contains("1234"));
        long accessed = now - TimeUnit.SECONDS.toMillis(30); //make it idle
        data.setAccessed(accessed);
        cache.release(session);
        assertTrue(cache.contains("1234"));
        try (AutoLock ignored = session.lock())
        {
            assertTrue(session.isResident());
        }
        cache.checkInactiveSession(session);
        assertFalse(cache.contains("1234"));
        try (AutoLock ignored = session.lock())
        {
            assertFalse(session.isResident());
        }
        SessionData retrieved = store.load("1234");
        assertEquals(accessed, retrieved.getAccessed()); //check that we persisted the session before we evicted
    }
    
    /**
     * Test that when saveOnEviction=true, if the save fails, the session
     * remains in the cache and can be used later.
     * @throws Exception if there is an unspecified problem
     */
    @Test
    public void testSaveOnEvictionFail() throws Exception
    {
        Server server = new Server();

        TestableSessionManager sessionManager = new TestableSessionManager();
        sessionManager.setServer(server);
        
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.EVICT_ON_INACTIVITY); //evict after 1 second inactivity
        cacheFactory.setSaveOnInactiveEviction(true);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionManager);

        //test values: allow first save, fail evict save, allow save
        FailableSessionDataStore sessionDataStore = new FailableSessionDataStore(new boolean[]{true, true, false, true});
        cache.setSessionDataStore(sessionDataStore);
        sessionManager.setSessionCache(cache);
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();
        
        //make a session
        long now = System.currentTimeMillis();
        SessionData data = sessionDataStore.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        ManagedSession session = cache.newSession(data);
        String id = session.getId();
        cache.add("1234", session); //make it resident
        session.setAttribute("aaa", "one");
        assertTrue(cache.contains("1234"));
        long accessed = now - TimeUnit.SECONDS.toMillis(30); //make it idle
        data.setAccessed(accessed);
        cache.commit(session);
        cache.release(session); //write the session, start the idle timer
        long lastSaved = data.getLastSaved();

        try (StacklessLogging ignored = new StacklessLogging(FileSessionsTest.class.getPackage()))
        {
            //wait for the idle timer to go off
            Thread.currentThread().sleep(SessionCache.EVICT_ON_INACTIVITY * 1500);

            //write out on evict will fail
            //check that session is still in the cache
            assertTrue(cache.contains(id));
            //check that the last-saved time didn't change
            assertEquals(lastSaved, data.getLastSaved());

            //test that the session can be mutated and saved
            session = cache.getAndEnter(id, true);
            assertNotNull(session);
            assertEquals("one", session.getAttribute("aaa"));
            session.setAttribute("aaa", "two");
            cache.commit(session);
            cache.release(session);

            //check that the save succeeded
            assertTrue(data.getLastSaved() > lastSaved);
        }
    }

    @Test
    public void testFlushOnResponseCommitDefault() throws Exception
    {
        //test factory defaults to flushOnResponseCommit==true
        DefaultSessionCacheFactory sessionCacheFactory = new DefaultSessionCacheFactory();
        assertTrue(sessionCacheFactory.isFlushOnResponseCommit());

        //test cache produced by factory defaults to flushOnResponseCommit==true
        DefaultSessionCache cacheFromFactory = (DefaultSessionCache)sessionCacheFactory.newSessionCache(new TestableSessionManager());
        assertTrue(cacheFromFactory.isFlushOnResponseCommit());

        //test cache defaults to flushOnResponseCommit==true
        DefaultSessionCache sessionCache = new DefaultSessionCache(new TestableSessionManager());
        assertTrue(sessionCache.isFlushOnResponseCommit());
    }

}
