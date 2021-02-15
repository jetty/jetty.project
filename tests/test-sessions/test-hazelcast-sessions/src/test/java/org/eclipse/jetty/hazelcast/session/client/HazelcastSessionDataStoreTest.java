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

package org.eclipse.jetty.hazelcast.session.client;

import org.eclipse.jetty.hazelcast.session.HazelcastSessionDataStore;
import org.eclipse.jetty.hazelcast.session.HazelcastTestHelper;
import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * HazelcastSessionDataStoreTest
 */
public class HazelcastSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    HazelcastTestHelper _testHelper;

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return _testHelper.createSessionDataStoreFactory(true);
    }

    @BeforeEach
    public void setUp()
    {
        _testHelper = new HazelcastTestHelper();
    }

    @AfterEach
    public void shutdown()
    {
        _testHelper.tearDown();
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        _testHelper.createSession(data);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        // not used by testLoadSessionFails()
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return _testHelper.checkSessionExists(data);
    }

    @Test
    @Override
    public void testGetExpiredDifferentNode() throws Exception
    {
        //This test will not work for hazelcast because we can't enable
        //HazelcastSessionDataStore.setScavengeZombieSessions, as it's
        //too difficult to get the required classes onto the embedded
        //hazelcast instance: these classes are required  to handle 
        //the serialization/deserialization that hazelcast performs when querying
        //to find zombie sessions.
    }

    @Test
    @Override
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {
        //This test will not work for hazelcast because we can't enable
        //HazelcastSessionDataStore.setScavengeZombieSessions, as it's
        //too difficult to get the required classes onto the embedded
        //hazelcast instance: these classes are required  to handle 
        //the serialization/deserialization that hazelcast performs when querying
        //to find zombie sessions.
    }

    @Override
    public void testStoreSession() throws Exception
    {
        //This test will not work for hazelcast because we can't enable
        //HazelcastSessionDataStore.setScavengeZombieSessions, as it's
        //too difficult to get the required classes onto the embedded
        //hazelcast instance: these classes are required  to handle 
        //the serialization/deserialization that hazelcast performs when querying
        //to find zombie sessions.
    }

    @Override
    public void testStoreObjectAttributes() throws Exception
    {
        //This test will not work for hazelcast because we can't enable
        //HazelcastSessionDataStore.setScavengeZombieSessions, as it's
        //too difficult to get the required classes onto the embedded
        //hazelcast instance: these classes are required  to handle
        //the serialization/deserialization that hazelcast performs when querying
        //to find zombie sessions.
    }

    /**
     * This test deliberately sets the sessionDataMap to null for the
     * HazelcastSessionDataStore to provoke an exception in the load() method.
     */
    @Override
    @Test
    public void testLoadSessionFails() throws Exception
    {
        // create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        // persist a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now - 1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);

        store.start();

        ((HazelcastSessionDataStore)store).setSessionDataMap(null);

        // test that loading it fails
        try
        {
            store.load("222");
            fail("Session should be unreadable");
        }
        catch (UnreadableSessionDataException e)
        {
            // expected exception
        }
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        return _testHelper.checkSessionPersisted(data);
    }
}
