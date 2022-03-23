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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NullSessionCacheTest
 */
public class NullSessionCacheTest extends AbstractSessionCacheTest
{    
    @Override
    public AbstractSessionCacheFactory newSessionCacheFactory(int evictionPolicy, boolean saveOnCreate,
                                                              boolean saveOnInactiveEvict, boolean removeUnloadableSessions,
                                                              boolean flushOnResponseCommit)
    {
        NullSessionCacheFactory factory = new NullSessionCacheFactory();
        factory.setSaveOnCreate(saveOnCreate);
        factory.setRemoveUnloadableSessions(removeUnloadableSessions);
        factory.setFlushOnResponseCommit(flushOnResponseCommit);
        return factory;
    }

    @Override
    public void checkSessionBeforeShutdown(String id,
                                           TestableSessionDataStore store,
                                           SessionCache cache,
                                           TestableSessionHandler sessionHandler) throws Exception
    {
        assertFalse(cache.contains(id)); //NullSessionCache never caches
        assertTrue(store._map.containsKey(id));
        assertFalse(sessionHandler._sessionDestroyedListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionPassivationListenersCalled.contains(id));
        assertFalse(sessionHandler._sessionActivationListenersCalled.contains(id)); //NullSessionCache always evicts on release, so never reactivates
    }

    @Override
    public void checkSessionAfterShutdown(String id,
                                          TestableSessionDataStore store,
                                          SessionCache cache,
                                          TestableSessionHandler sessionHandler) throws Exception
    {
        assertFalse(cache.contains(id)); //NullSessionCache never caches anyway
        assertTrue(store._map.containsKey(id)); //NullSessionCache doesn't do anything on shutdown, so session should still exit
        assertFalse(sessionHandler._sessionDestroyedListenersCalled.contains(id)); //Session should still exit
        assertEquals(1L, sessionHandler._sessionPassivationListenersCalled.stream().filter(s -> s.equals(id)).count());
        assertFalse(sessionHandler._sessionActivationListenersCalled.contains(id)); //NullSessionCache always evicts on release, so never reactivates
    }
    
    @Test
    public void testNotCached() throws Exception
    {
        //Test the NullSessionCache never contains the session
        Server server = new Server();

        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        sessionHandler.setServer(server);
        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(sessionHandler);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionHandler.setSessionCache(cache);
        server.setHandler(sessionHandler);
        server.start();

        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(data); //mimic a request making a session
        cache.add("1234", session);
        assertFalse(cache.contains("1234")); //null cache doesn't actually retain the session
        
        //mimic releasing the session after the request is finished
        cache.release(session);
        assertTrue(store.exists("1234"));
        assertFalse(cache.contains("1234"));

        //simulate a new request using the previously created session
        session = cache.get("1234"); //get the session again
        session.access(now); //simulate a request
        cache.release(session); //finish with the session
        assertFalse(cache.contains("1234"));
        assertFalse(session.isResident());
    }
    
    /**
     * Test contains method.
     */
    @Test
    public void testContains()
        throws Exception
    {
        Server server = new Server();

        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        sessionHandler.setServer(server);
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = (SessionCache)cacheFactory.getSessionCache(sessionHandler);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionHandler.setSessionCache(cache);
        server.setHandler(sessionHandler);
        server.start();
        
        //test one that doesn't exist
        assertFalse(cache.contains("1234"));

        //test one that exists
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        Session session = cache.newSession(data);
        cache.add("1234", session);
        assertFalse(cache.contains("1234"));
    }
    
    /**
     * Test the exist method.
     */
    @Test
    public void testExists()
        throws Exception
    {
        Server server = new Server();

        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        sessionHandler.setServer(server);
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, false, false, false, false);
        SessionCache cache = (SessionCache)cacheFactory.getSessionCache(sessionHandler);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionHandler.setSessionCache(cache);
        server.setHandler(sessionHandler);
        server.start();


        //test one that doesn't exist anywhere at all
        assertFalse(cache.exists("1234"));

        //test one that only exists in the store
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        store.store("1234", data);
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

        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        sessionHandler.setServer(server);
        SessionCacheFactory cacheFactory = newSessionCacheFactory(SessionCache.NEVER_EVICT, true, false, false, false);
        SessionCache cache = cacheFactory.getSessionCache(sessionHandler);

        TestableSessionDataStore store = new TestableSessionDataStore();
        cache.setSessionDataStore(store);
        sessionHandler.setSessionCache(cache);
        server.setHandler(sessionHandler);
        server.start();

        //test remove non-existent session
        Session session = cache.delete("1234");
        assertNull(session);

        //test remove of existing session in store only
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        store.store("1234", data);
        session = cache.delete("1234");
        assertNull(session); //NullSessionCache never returns the session that was removed from the cache because it was never in the cache!
        assertFalse(store.exists("1234"));
        assertFalse(cache.contains("1234"));
    }
}
