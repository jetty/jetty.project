//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
    public HazelcastSessionDataStoreTest() throws Exception
    {
        super();
    }

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
    public void testStoreSession() throws Exception
    {
       /*
        * This test does not work with hazelcast, because it uses session attributes
        * that are classes that are only on the webapp's classloader. Unfortunately
        * it seems impossible to get hazelcast to use the thread context classloader
        * when deserializing sessions: it is only using the System classloader.
        */
    }

    @Test
    @Override
    public void testStoreObjectAttributes() throws Exception
    {
        /*
         * This test does not work with hazelcast, because it uses session attributes
         * that are classes that are only on the webapp's classloader. Unfortunately
         * it seems impossible to get hazelcast to use the thread context classloader
         * when deserializing sessions: it is only using the System classloader.
         */
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
        SessionData data = store.newSessionData("fff", 100, now, now - 1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);

        store.start();

        ((HazelcastSessionDataStore)store).setSessionDataMap(null);

        // test that loading it fails
        try
        {
            store.load("fff");
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
