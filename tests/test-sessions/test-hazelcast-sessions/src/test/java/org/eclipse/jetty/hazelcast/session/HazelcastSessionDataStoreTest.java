//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.hazelcast.session;

import static org.junit.jupiter.api.Assertions.fail;

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

/**
 * HazelcastSessionDataStoreTest
 *
 *
 */
public class HazelcastSessionDataStoreTest extends AbstractSessionDataStoreTest
{
   
    HazelcastTestHelper _testHelper;

   
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {      
        return _testHelper.createSessionDataStoreFactory(false);
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
        //not used by testLoadSessionFails()
    }

   
    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return _testHelper.checkSessionExists(data);
    }

    /** 
     * 
     * This test deliberately sets the sessionDataMap to null
     * for the HazelcastSessionDataStore to provoke an exception
     * in the load() method.
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
        SessionData data = store.newSessionData("222", 100, now, now-1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        store.start();
        
        ((HazelcastSessionDataStore)store).setSessionDataMap(null);
        

        //test that loading it fails
        try
        {
            store.load("222");
            fail("Session should be unreadable");
        }
        catch (UnreadableSessionDataException e)
        {
            //expected exception
        }
    }
    
    
    /** 
     * This test currently won't work for Hazelcast - there is currently no
     * means to query it to find sessions that have expired.
     * 
     */
    @Override
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {
        //ignore
    }

    
    

    /** 
     * This test currently won't work for Hazelcast - there is currently no
     * means to query it to find sessions that have expired.
     */
    @Override
    public void testGetExpiredDifferentNode() throws Exception
    {
        //ignore
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        return _testHelper.checkSessionPersisted(data);
    }
}
