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

import java.io.IOException;
import java.io.Serializable;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        
        TestableSessionHandler sessionHandler = new TestableSessionHandler();
        TestableSessionDataStore sessionDataStore = new TestableSessionDataStore(true);
        DefaultSessionCache sessionCache = new DefaultSessionCache(sessionHandler);
        sessionCache.setSaveOnCreate(true); //ensure session will be saved when first created
        sessionCache.setSessionDataStore(sessionDataStore);
        sessionHandler.setSessionCache(sessionCache);
        
        server.setHandler(sessionHandler);
        server.start();
        
        //make a session
        Session session = sessionHandler.newSession(null, "1234");
        String id = session.getId();
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionHandler._sessionCreatedListenersCalled.contains(id));
        //NOTE:  we don't call passivate and activate here because the session by definition 
        //_cannot_ contain any attributes, we have literally only just created it
        
        sessionHandler.clear();
        
        //Mutate an attribute in the same request
        session.setAttribute("aaa", "one");
        sessionHandler.commit(session);
        sessionHandler.complete(session);
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionHandler._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionActivationListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionBoundListenersCalled.contains(id));
        assertFalse(sessionHandler._sessionUnboundListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionAttributeListenersCalled.contains(id));

        sessionHandler.clear();
        
        //simulate another request mutating the same attribute to the same value
        session = sessionCache.getAndEnter(id, true);
        session.setAttribute("aaa", "one");
        sessionHandler.commit(session);
        sessionHandler.complete(session);
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionHandler._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionActivationListenersCalled.contains(id));
        assertFalse(sessionHandler._sessionUnboundListenersCalled.contains(id));
        assertFalse(sessionHandler._sessionBoundListenersCalled.contains(id));
        assertFalse(sessionHandler._sessionAttributeListenersCalled.contains(id));

        sessionHandler.clear();
        
        //simulate another request mutating to a different value
        session = sessionCache.getAndEnter(id, true);
        session.setAttribute("aaa", "two");
        sessionHandler.commit(session);
        sessionHandler.complete(session);
        assertTrue(session.isValid());
        assertTrue(sessionDataStore.exists(id));
        assertTrue(sessionHandler._sessionPassivationListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionActivationListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionUnboundListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionBoundListenersCalled.contains(id));
        assertTrue(sessionHandler._sessionAttributeListenersCalled.contains(id));
    }
}
