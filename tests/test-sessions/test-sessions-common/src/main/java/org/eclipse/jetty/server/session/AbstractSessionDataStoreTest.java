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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", 100, now, now-1, -1);//never expires
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data); 
        
        store.start();
        
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        
        //persist a session that is expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("678", 100, now-20, now-30, 10);//10 sec max idle
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(now-2); //make it expired recently
        persistSession(data);
        
        store.start();
        
        //test we can retrieve it
        SessionData loaded = store.load("678");
        assertNotNull(loaded);
        assertEquals("678", loaded.getId());
        assertEquals(100, loaded.getCreated());
        assertEquals(now-20, loaded.getAccessed());
        assertEquals(now-30, loaded.getLastAccessed());
        assertEquals(now-2, loaded.getExpiry());
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);


        //persist a session that is damaged and cannot be read
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now-1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistUnreadableSession(data);
        
        store.start();

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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", 100, now, now-1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        store.start();
        
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
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
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is expired
        SessionData data = store.newSessionData("1234", 100, 101, 101, 10);
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(102); //make it expired long ago
        persistSession(data);
        
        //persist another session that is expired
        SessionData data2 = store.newSessionData("5678", 100, 100, 101, 30);
        data2.setLastNode(sessionContext.getWorkerName());
        data2.setExpiry(102); //make it expired long ago
        persistSession(data2);
        
        store.start();
        
        Set<String> candidates = new HashSet<>(Arrays.asList(new String[] {"1234", "5678"}));
        Set<String> expiredIds = store.getExpired(candidates);
        assertEquals(2, expiredIds.size());
        assertTrue(expiredIds.contains("1234"));
        assertTrue(expiredIds.contains("5678"));
    }
    

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates are not expired in the store.
     * @throws Exception
     */
    public void testGetExpiredPersistedNotExpired() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        long now = System.currentTimeMillis();
        //persist a session that is not expired
        SessionData data = store.newSessionData("1234", 100, now, now-1, 30);
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        //persist another session that is not expired
        SessionData data2 = store.newSessionData("5678", 100, now, now-1, 30);
        data2.setLastNode(sessionContext.getWorkerName());
        persistSession(data2);
        
        store.start();
        
        Set<String> candidates = new HashSet<>(Arrays.asList(new String[] {"1234", "5678"}));
        Set<String> expiredIds = store.getExpired(candidates);
        assertEquals(0, expiredIds.size());
    }
    
    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates don't exist in the store.
     * 
     * @throws Exception
     */
    public void testGetExpiredNotPersisted() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        store.start();
        
        Set<String> candidates = new HashSet<>(Arrays.asList(new String[] {"1234", "5678"}));
        Set<String> expiredIds = store.getExpired(candidates);
        assertEquals(2, expiredIds.size());
        assertTrue(expiredIds.contains("1234"));
        assertTrue(expiredIds.contains("5678"));
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
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec((int)TimeUnit.HOURS.toSeconds(2));
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is expired
        SessionData data = store.newSessionData("1234", 100, 101, 100, 30);
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(1000);
        persistSession(data);
        
        //persist another session that is expired
        SessionData data2 = store.newSessionData("5678", 100, 101, 100, 30);
        data2.setLastNode(sessionContext.getWorkerName());
        data2.setExpiry(1000);
        persistSession(data2);
        
        store.start();
        
        Set<String> candidates = new HashSet<>();
        Set<String> expiredIds = store.getExpired(candidates);
        assertEquals(2, expiredIds.size());
        assertTrue(expiredIds.contains("1234"));
        assertTrue(expiredIds.contains("5678"));
    }
}
