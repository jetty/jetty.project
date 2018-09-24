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


package org.eclipse.jetty.server.session;

import static org.junit.jupiter.api.Assertions.fail;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * InfinispanSessionDataStoreTest
 *
 *
 */
public class InfinispanSessionDataStoreTest extends AbstractSessionDataStoreTest
{    
    
    public InfinispanTestSupport __testSupport;
    
    @BeforeEach
    public void setup () throws Exception
    {
        __testSupport = new InfinispanTestSupport();
        __testSupport.setup();
    }
    
    @AfterEach
    public void teardown () throws Exception
    {
        __testSupport.teardown();
    }

   
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        InfinispanSessionDataStoreFactory factory = new InfinispanSessionDataStoreFactory();
        factory.setCache(__testSupport.getCache());
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
        SessionData data = store.newSessionData("222", 100, now, now-1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        store.start();
        
        ((InfinispanSessionDataStore)store).setCache(null);
        

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
        return __testSupport.checkSessionPersisted(data);
    }
    
}
