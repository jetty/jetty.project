//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

/**
 * AbstractSessionDataStoreTest
 *
 *
 */
public abstract class AbstractSessionDataStoreTest
{

    public abstract SessionDataStoreFactory createSessionDataStoreFactory();
    
    public abstract void persistSession(SessionData data) throws Exception;
    
    public abstract void persistUnreadableSession(SessionData data) throws Exception;
    
    public abstract boolean checkSessionPersisted (SessionData data) throws Exception;
    


    /**
     * Test that we can load a persisted session.
     * 
     * @throws Exception
     */
    @Test
    public  void testLoadSessionExists() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        store.initialize(new SessionContext("foo", context.getServletContext()));
        store.start();
        
        //persist a session that is not expired
        long now = System.currentTimeMillis();
        persistSession(store.newSessionData("1234", 100, now, now-1, -1)); //never expires
        
        //test that we can retrieve it
        SessionData loaded = store.load("1234");
        assertNotNull(loaded);
        assertEquals("1234", loaded.getId());
        assertEquals(100, loaded.getCreated());
        assertEquals(now, loaded.getAccessed());
        assertEquals(now-1, loaded.getLastAccessed());
        assertEquals(0, loaded.getExpiry());
    }
    
    
    /**
     * Test that an expired session can be loaded.
     * 
     * @throws Exception
     */
    @Test
    public void testLoadSessionExpired() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        store.initialize(new SessionContext("foo", context.getServletContext()));
        store.start();
        
        
        //persist a session that is expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("678", 100, now, now-1, 10);//10 sec max idle
        data.setExpiry(102); //make it expired long ago
        persistSession(data);
        
        //test we can retrieve it
        SessionData loaded = store.load("678");
        assertNotNull(loaded);
        assertEquals("678", loaded.getId());
        assertEquals(100, loaded.getCreated());
        assertEquals(now, loaded.getAccessed());
        assertEquals(now-1, loaded.getLastAccessed());
        assertEquals(102, loaded.getExpiry());
    }
    
    
    /**
     * Test that a non-existent session cannot be loaded.
     * @throws Exception
     */
    @Test
    public  void testLoadSessionDoesNotExist() throws Exception
    { 
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        store.initialize(new SessionContext("foo", context.getServletContext()));
        store.start();
        
        //test we can't retrieve a non-existent session
        SessionData loaded = store.load("111");
        assertNull(loaded);
    }
    
    
    /**
     * Test that a session that cannot be loaded throws exception.
     * @throws Exception
     */
    public void testLoadSessionFails() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        store.initialize(new SessionContext("foo", context.getServletContext()));
        store.start();

        //persist a session that is damaged and cannot be read
        long now = System.currentTimeMillis();
        persistUnreadableSession(store.newSessionData("222", 100, now, now-1, -1));

        //test that we can retrieve it
        try
        {
            SessionData loaded = store.load("222");
            fail("Session should be unreadable");
        }
        catch (UnreadableSessionDataException e)
        {
            //expected exception
        }
    }

    
    
    /**
     * Test that we can delete a persisted session.
     * 
     * @throws Exception
     */
    @Test
    public void testDeleteSessionExists() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        store.initialize(new SessionContext("foo", context.getServletContext()));
        store.start();
        
        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", 100, now, now-1, -1);
        persistSession(data);
        
        //delete the session via the store
        store.delete("1234");
        
        //check the session is no longer persisted
        assertFalse(checkSessionPersisted(data));
    }
    
    
    /**
     * Test deletion of non-existent session.
     * @throws Exception
     */
    @Test
    public void testDeleteSessionDoesNotExist() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        store.initialize(new SessionContext("foo", context.getServletContext()));
        store.start();
        
        //delete the non-existent session via the store
        store.delete("3333");
    }
    
    
    /**
     * Test SessionDataStore.getExpired.  Tests the situation
     * where the session candidates are also expired in the
     * store.
     * 
     * @throws Exception
     */
    public void testGetExpiredPersistedAndExpired() throws Exception
    {
        
    }
    

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates are not expired in the store.
     * @throws Exception
     */
    public void testGetExpiredPersistedNotExpired() throws Exception
    {
        
    }
    
    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates don't exist in the store.
     * 
     * @throws Exception
     */
    public void testGetExpiredNotPersisted() throws Exception
    {
        
    }
    
    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * there are more persisted expired sessions in the store than
     * present in the candidate list.
     * 
     * @throws Exception
     */
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {
        
    }
}
