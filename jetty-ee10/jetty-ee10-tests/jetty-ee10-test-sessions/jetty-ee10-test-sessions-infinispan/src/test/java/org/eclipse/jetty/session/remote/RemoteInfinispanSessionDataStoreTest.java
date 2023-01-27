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

package org.eclipse.jetty.session.remote;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.LoggingUtil;
import org.eclipse.jetty.session.SessionContext;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.UnreadableSessionDataException;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.RemoteQueryManager;
import org.infinispan.client.hotrod.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.QueryResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
    static
    {
        LoggingUtil.init();
    }

    public static RemoteInfinispanTestSupport __testSupport;

    public RemoteInfinispanSessionDataStoreTest() throws Exception
    {
        super();
    }

    @BeforeAll
    public static void initRemoteSupport() throws Exception
    {
        __testSupport = new RemoteInfinispanTestSupport("remote-session-test");
    }

    @BeforeEach
    public void configure() throws Exception
    {
        __testSupport.setup();
    }

    @AfterEach
    public void teardown() throws Exception
    {
        __testSupport.teardown();
    }

    @AfterAll
    public static void shutdown() throws Exception
    {
        __testSupport.shutdown();
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
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            __testSupport.createSession((InfinispanSessionData)data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
        
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        //Not used by testLoadSessionFails() 
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return __testSupport.checkSessionExists((InfinispanSessionData)data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
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
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(new Server());
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.getSessionHandler().setSessionIdManager(idMgr);
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext(context.getSessionHandler());
        store.initialize(sessionContext);

        //persist a session
        long now = System.currentTimeMillis();
        InfinispanSessionData data = (InfinispanSessionData)store.newSessionData("222", 100, now, now - 1, -1);
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
        sd1.serializeAttributes();
        __testSupport.getCache().put("session1", sd1);

        InfinispanSessionData sd2 = new InfinispanSessionData("sd2", "", "", 0, 0, 0, 2000);
        sd2.setLastNode("fred2");
        sd2.serializeAttributes();
        __testSupport.getCache().put("session2", sd2);

        InfinispanSessionData sd3 = new InfinispanSessionData("sd3", "", "", 0, 0, 0, 3000);
        sd3.setLastNode("fred3");
        sd3.serializeAttributes();
        __testSupport.getCache().put("session3", sd3);

        QueryFactory qf = Search.getQueryFactory(__testSupport.getCache());
        Query<InfinispanSessionData> query = qf.create("from org_eclipse_jetty_session_infinispan.InfinispanSessionData where " +
            " expiry < :time");

        for (int i = 0; i <= 3; i++)
        {
            long now = System.currentTimeMillis();
            query.setParameter("time", now);
            QueryResult<InfinispanSessionData> result = query.execute();
            assertEquals(i, result.list().size());
            Thread.sleep(1000);
        }
    }
}


