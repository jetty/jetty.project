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

package org.eclipse.jetty.server.session.remote;

import org.eclipse.jetty.server.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.server.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.UnreadableSessionDataException;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.RemoteQueryManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RemoteInfinispanSessionDataStoreTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class RemoteInfinispanSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    public static RemoteInfinispanTestSupport __testSupport;

    @BeforeEach
    public void setup() throws Exception
    {
        __testSupport = new RemoteInfinispanTestSupport("remote-session-test");
        __testSupport.setup();
    }

    @AfterEach
    public void teardown() throws Exception
    {
        __testSupport.teardown();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(__testSupport.getCache());
        factory.setQueryManager(new RemoteQueryManager(__testSupport.getCache()));
        return factory;
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        __testSupport.createSession(data);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        //Not used by testLoadSessionFails() 
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return __testSupport.checkSessionExists(data);
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return __testSupport.checkSessionPersisted(data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    /**
     * This test deliberately sets the infinispan cache to null to
     * try and provoke an exception in the InfinispanSessionDataStore.load() method.
     */
    @Override
    public void testLoadSessionFails() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        //persist a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now - 1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);

        store.start();

        ((InfinispanSessionDataStore)store).setCache(null);

        //test that loading it fails
        assertThrows(UnreadableSessionDataException.class, () -> store.load("222"));
    }

    @Test
    public void testQuery() throws Exception
    {
        InfinispanSessionData sd1 = new InfinispanSessionData("sd1", "", "", 0, 0, 0, 1000);
        sd1.setLastNode("fred1");
        __testSupport.getCache().put("session1", sd1);

        InfinispanSessionData sd2 = new InfinispanSessionData("sd2", "", "", 0, 0, 0, 2000);
        sd2.setLastNode("fred2");
        __testSupport.getCache().put("session2", sd2);

        InfinispanSessionData sd3 = new InfinispanSessionData("sd3", "", "", 0, 0, 0, 3000);
        sd3.setLastNode("fred3");
        __testSupport.getCache().put("session3", sd3);

        QueryFactory qf = Search.getQueryFactory(__testSupport.getCache());

        for (int i = 0; i <= 3; i++)
        {
            long now = System.currentTimeMillis();
            Query q = qf.from(InfinispanSessionData.class).having("expiry").lt(now).build();
            assertEquals(i, q.list().size());
            Thread.sleep(1000);
        }
    }
}


