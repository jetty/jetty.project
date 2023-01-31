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

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.infinispan.EmbeddedQueryManager;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.session.infinispan.QueryManager;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SerializedInfinispanSessionDataStoreTest
 */
@ExtendWith(WorkDirExtension.class)
public class SerializedInfinispanSessionDataStoreTest extends AbstractSessionDataStoreTest
{
    public InfinispanTestSupport _testSupport;

    public WorkDir workDir;

    public SerializedInfinispanSessionDataStoreTest() throws Exception
    {
        super();
    }
    
    @BeforeEach
    public void setup() throws Exception
    {
        _testSupport = new InfinispanTestSupport();
        _testSupport.setSerializeSessionData(true);
        _testSupport.setup(workDir.getEmptyPathDir());
    }

    @AfterEach
    public void teardown() throws Exception
    {
        _testSupport.teardown();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(_testSupport.getCache());
        factory.setSerialization(true);
        QueryManager qm = new EmbeddedQueryManager(_testSupport.getCache());
        factory.setQueryManager(qm);
        return factory;
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            _testSupport.createSession(data);
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
            return _testSupport.checkSessionExists(data);
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
        context.getSessionHandler().getSessionManager().setSessionIdManager(idMgr);
        context.setClassLoader(_contextClassLoader);
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler().getSessionManager());
        SessionContext sessionContext = new SessionContext(context.getSessionHandler().getSessionManager());
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

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return _testSupport.checkSessionPersisted(data);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testQuery() throws Exception
    {
        InfinispanSessionData sd1 = new InfinispanSessionData("sd1", "", "", 0, 0, 0, 1000);
        sd1.setLastNode("fred1");
        sd1.serializeAttributes();
        _testSupport.getCache().put("session1", sd1);

        InfinispanSessionData sd2 = new InfinispanSessionData("sd2", "", "", 0, 0, 0, 2000);
        sd2.setLastNode("fred2");
        sd2.serializeAttributes();
        _testSupport.getCache().put("session2", sd2);

        InfinispanSessionData sd3 = new InfinispanSessionData("sd3", "", "", 0, 0, 0, 3000);
        sd3.setLastNode("fred3");
        sd3.serializeAttributes();
        _testSupport.getCache().put("session3", sd3);

        QueryFactory qf = Search.getQueryFactory(_testSupport.getCache());

        for (int i = 0; i <= 3; i++)
        {
            long now = System.currentTimeMillis();
            Query<InfinispanSessionData> q = qf.create("from org.eclipse.jetty.session.infinispan.InfinispanSessionData where expiry < " + now);
            QueryResult<InfinispanSessionData> result = q.execute();
            assertEquals(i, result.list().size());
            Thread.sleep(1000);
        }
    }
}
