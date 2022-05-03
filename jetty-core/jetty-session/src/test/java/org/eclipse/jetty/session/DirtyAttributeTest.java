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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DirtyAttributeTest
 *
 * Check that repeated calls to setAttribute when we never evict the
 * session from the cache still result in writes.
 */
public class DirtyAttributeTest
{
    @Test
    public void testDirtyWrite() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idManager = new DefaultSessionIdManager(server);
        server.addBean(idManager, true);
        
        TestableSessionManager sessionManager = new TestableSessionManager();
        TestableSessionDataStore sessionDataStore = new TestableSessionDataStore(true);
        DefaultSessionCache sessionCache = new DefaultSessionCache(sessionManager);
        sessionCache.setSaveOnCreate(true); //ensure session will be saved when first created
        sessionCache.setSessionDataStore(sessionDataStore);
        sessionManager.setSessionCache(sessionCache);
        
        server.addBean(sessionManager);
        sessionManager.setServer(server);
        server.start();
        
        //make a session
        Session session = sessionManager.newSession(null, "1234");
        String id = session.getId();
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionManager._sessionCreatedListenersCalled.contains(id));
        //NOTE:  we don't call passivate and activate here because the session by definition 
        //_cannot_ contain any attributes, we have literally only just created it
        
        sessionManager.clear();
        
        //Mutate an attribute in the same request
        session.setAttribute("aaa", "one");
        sessionManager.commit(session);
        sessionManager.complete(session);
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionManager._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionManager._sessionActivationListenersCalled.contains(id));
        assertTrue(sessionManager._sessionBoundListenersCalled.contains(id));
        assertFalse(sessionManager._sessionUnboundListenersCalled.contains(id));
        assertTrue(sessionManager._sessionAttributeListenersCalled.contains(id));

        sessionManager.clear();
        
        //simulate another request mutating the same attribute to the same value
        session = sessionCache.getAndEnter(id, true);
        session.setAttribute("aaa", "one");
        sessionManager.commit(session);
        sessionManager.complete(session);
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionManager._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionManager._sessionActivationListenersCalled.contains(id));
        assertFalse(sessionManager._sessionUnboundListenersCalled.contains(id));
        assertFalse(sessionManager._sessionBoundListenersCalled.contains(id));
        assertFalse(sessionManager._sessionAttributeListenersCalled.contains(id));

        sessionManager.clear();
        
        //simulate another request mutating to a different value
        session = sessionCache.getAndEnter(id, true);
        session.setAttribute("aaa", "two");
        sessionManager.commit(session);
        sessionManager.complete(session);
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionManager._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionManager._sessionActivationListenersCalled.contains(id));
        assertTrue(sessionManager._sessionUnboundListenersCalled.contains(id));
        assertTrue(sessionManager._sessionBoundListenersCalled.contains(id));
        assertTrue(sessionManager._sessionAttributeListenersCalled.contains(id));
    }
}
