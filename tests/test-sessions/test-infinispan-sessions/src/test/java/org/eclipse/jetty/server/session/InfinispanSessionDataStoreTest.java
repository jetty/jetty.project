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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.session.infinispan.InfinispanSessionData;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * InfinispanSessionDataStoreTest
 */
@ExtendWith(WorkDirExtension.class)
public class InfinispanSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    public InfinispanTestSupport _testSupport;

    public WorkDir workDir;

    @BeforeEach
    public void setup() throws Exception
    {
        _testSupport = new InfinispanTestSupport();
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
        return factory;
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        _testSupport.createSession(data);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        //Not used by testLoadSessionFails() 
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return _testSupport.checkSessionExists(data);
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

    /**
     * This test currently won't work for Infinispan - there is currently no
     * means to query it to find sessions that have expired.
     *
     * @see org.eclipse.jetty.server.session.AbstractSessionDataStoreTest#testGetExpiredPersistedAndExpiredOnly()
     */
    @Override
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {

    }

    /**
     * This test won't work for Infinispan - there is currently no
     * means to query infinispan to find other expired sessions.
     */
    @Override
    public void testGetExpiredDifferentNode() throws Exception
    {
        //Ignore
    }

    /**
     *
     */
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
        _testSupport.getCache().put("session1", sd1);

        InfinispanSessionData sd2 = new InfinispanSessionData("sd2", "", "", 0, 0, 0, 2000);
        sd2.setLastNode("fred2");
        _testSupport.getCache().put("session2", sd2);

        InfinispanSessionData sd3 = new InfinispanSessionData("sd3", "", "", 0, 0, 0, 3000);
        sd3.setLastNode("fred3");
        _testSupport.getCache().put("session3", sd3);

        QueryFactory qf = Search.getQueryFactory(_testSupport.getCache());

        for (int i = 0; i <= 3; i++)
        {
            long now = System.currentTimeMillis();
            Query q = qf.from(InfinispanSessionData.class).having("expiry").lt(now).build();
            assertEquals(i, q.list().size());
            Thread.sleep(1000);
        }
    }
}
