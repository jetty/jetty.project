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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SessionListenerTest
 *
 * Test that session listeners are called.
 */
public class SessionListenerTest
{

    /**
     * Test that listeners are called when a session is deliberately invalidated.
     */
    @Test
    public void testListenerWithInvalidation() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        server.addBean(idManager, true);

        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        sessionHandler.setServer(server);
        sessionHandler.setMaxInactiveInterval(6);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionHandler);

        TestableSessionDataStore store = new TestableSessionDataStore(false);
        cache.setSessionDataStore(store);
        sessionHandler.setSessionCache(cache);
        server.setHandler(sessionHandler);
        server.start();

        try
        {
            //create the session, test that creation listener is called
            Session session = sessionHandler.newSession(null, "1234");
            session.setAttribute("a", "one");
            assertEquals(1L, sessionHandler._sessionCreatedListenersCalled.stream().filter(s -> s.equals(session.getId())).count());
            assertEquals(1L, sessionHandler._sessionBoundListenersCalled.stream().filter(s -> s.equals(session.getId())).count());
            assertEquals(1L, sessionHandler._sessionAttributeListenersCalled.stream().filter(s -> s.equals(session.getId())).count());
            
            sessionHandler.clear();

            //invalidate the session, test that destroy listener is called, and unbinding listener called
            session.invalidate();
            assertEquals(1L, sessionHandler._sessionDestroyedListenersCalled.stream().filter(s -> s.equals(session.getId())).count());
            assertEquals(1L, sessionHandler._sessionUnboundListenersCalled.stream().filter(s -> s.equals(session.getId())).count());
            assertEquals(1L, sessionHandler._sessionAttributeListenersCalled.stream().filter(s -> s.equals(session.getId())).count());
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    /**
     * Check that a session that is expired has listeners called for it when it is attempted to be used
     */
    @Test
    public void testExpiredSession() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        server.addBean(idManager, true);

        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        sessionHandler.setServer(server);
        sessionHandler.setMaxInactiveInterval(6);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        DefaultSessionCache cache = (DefaultSessionCache)cacheFactory.getSessionCache(sessionHandler);

        TestableSessionDataStore store = new TestableSessionDataStore(false);
        cache.setSessionDataStore(store);
        sessionHandler.setSessionCache(cache);
        server.setHandler(sessionHandler);
        server.start();

        try
        {

            //save a session that has already expired
            long now = System.currentTimeMillis();
            SessionData data = store.newSessionData("1234", now - 10, now - 5, now - 10, 30000);
            data.setAttribute("a", "one");
            data.setExpiry(100); //make it expired a long time ago
            store.store("1234", data);

            //try to get the expired session, ensure that it wasn't used and that listeners were called for its expiry
            Session session = sessionHandler.getSession("1234");
            assertNull(session);
            assertEquals(1L, sessionHandler._sessionDestroyedListenersCalled.stream().filter(s -> s.equals("1234")).count());
            assertEquals(1L, sessionHandler._sessionUnboundListenersCalled.stream().filter(s -> s.equals("1234")).count());
            assertEquals(1L, sessionHandler._sessionAttributeListenersCalled.stream().filter(s -> s.equals("1234")).count());
        }
        finally
        {
            server.stop();
        }
    }
}
