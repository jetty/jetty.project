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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
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
    public static final int GRACE_PERIOD_SEC = (int)TimeUnit.HOURS.toSeconds(2);
    
    /**
     * A timestamp representing a time that was close to the epoch, and thus
     * happened a long time ago. Used as expiry timestamp for to
     * signify a session is expired.
     */
    public static final long ANCIENT_TIMESTAMP = 100L;
    public static final long RECENT_TIMESTAMP = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(3*GRACE_PERIOD_SEC);
    

    
    
    public abstract SessionDataStoreFactory createSessionDataStoreFactory();
    
    public abstract void persistSession(SessionData data) throws Exception;
    
    public abstract void persistUnreadableSession(SessionData data) throws Exception;
    
    public abstract boolean checkSessionExists (SessionData data) throws Exception;
    
    public abstract boolean checkSessionPersisted (SessionData data) throws Exception;
    
    
    
    /**
     * Test that the store can persist a session.
     * 
     * @throws Exception 
     */
    @Test
    public void testStoreSession() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        store.start();
        
        //create a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", 100, now, now-1, -1);//never expires
        data.setAttribute("a", "b");
        data.setLastNode(sessionContext.getWorkerName());
        
        store.store("1234", data);
        
        //check that the store contains all of the session data
        assertTrue(checkSessionPersisted(data));
    }
    
    
    /**
     * Test that the store can update a pre-existing session.
     *  
     * @throws Exception
     */
    @Test
    public void testUpdateSession() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        store.start();
        
        //create a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", 100, 200, 199, -1);//never expires
        data.setAttribute("a", "b");
        data.setLastNode(sessionContext.getWorkerName());
        data.setLastSaved(400); //make it look like it was previously saved by the store
        
        System.err.println("Cookie set time:"+data.getCookieSet());
        
        //put it into the store
        persistSession(data);
        
        //now test we can update the session
        data.setLastAccessed(now-1);
        data.setAccessed(now);
        data.setAttribute("a", "c");
        store.store("1234", data);
        
        assertTrue(checkSessionPersisted(data));
    }
    
    
    /**
     * Test that the store can persist a session that contains 
     * serializable objects in the attributes.
     * 
     * @throws Exception
     */
    @Test
    public void testStoreObjectAttributes() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        store.start();
        
        //create a session
        SessionData data = store.newSessionData("1234", 100, 200, 199, -1);//never expires
        TestFoo testFoo = new TestFoo();
        testFoo.setInt(33);
        FooInvocationHandler handler = new FooInvocationHandler(testFoo);
        Foo foo = (Foo)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class[] {Foo.class}, handler);
        data.setAttribute("foo", foo);
        data.setLastNode(sessionContext.getWorkerName());
        
        //test that it can be persisted
        store.store("1234", data);
        checkSessionPersisted(data);
    }

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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        
        //persist a session that is expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("678", 100, now-20, now-30, 10);//10 sec max idle
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //make it expired recently
        persistSession(data);
        
        store.start();
        
        //test we can retrieve it
        SessionData loaded = store.load("678");
        assertNotNull(loaded);
        assertEquals("678", loaded.getId());
        assertEquals(100, loaded.getCreated());
        assertEquals(now-20, loaded.getAccessed());
        assertEquals(now-30, loaded.getLastAccessed());
        assertEquals(RECENT_TIMESTAMP, loaded.getExpiry());
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
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
    @Test
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


        //persist a session that is damaged and cannot be read
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now-1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        persistUnreadableSession(data);
        
        store.start();

        //test that we can retrieve it
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
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
        
        //check the session is no longer exists
        assertFalse(checkSessionExists(data));
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
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
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
    @Test
    public void testGetExpiredPersistedAndExpired() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is expired
        SessionData data = store.newSessionData("1234", 100, 101, 101, 10);
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //make it expired recently so FileSessionDataStore doesn't eliminate it on startup
        persistSession(data);
        
        //persist another session that is expired
        SessionData data2 = store.newSessionData("5678", 100, 100, 101, 30);
        data2.setLastNode(sessionContext.getWorkerName());
        data2.setExpiry(RECENT_TIMESTAMP); //make it expired recently so FileSessionDataStore doesn't eliminate it on startup
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
    @Test
    public void testGetExpiredPersistedNotExpired() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        long now = System.currentTimeMillis();
        //persist a session that is not expired
        SessionData data = store.newSessionData("1234", 100, now, now-1, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        //persist another session that is not expired
        SessionData data2 = store.newSessionData("5678", 100, now, now-1, TimeUnit.MINUTES.toMillis(60));
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
    @Test
    public void testGetExpiredNotPersisted() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
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
    @Test
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is expired
        SessionData data = store.newSessionData("1234", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //must be recently expired, or FileSessionDataStore will eliminate it on startup
        persistSession(data);
        
        //persist another session that is expired
        SessionData data2 = store.newSessionData("5678", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data2.setLastNode(sessionContext.getWorkerName());
        data2.setExpiry(RECENT_TIMESTAMP); //must be recently expired, or FileSessionDataStore will eliminate it on startup
        persistSession(data2);
        
        store.start();
        
        Set<String> candidates = new HashSet<>();
        Set<String> expiredIds = store.getExpired(candidates);
        assertEquals(2, expiredIds.size());
        assertTrue(expiredIds.contains("1234"));
        assertTrue(expiredIds.contains("5678"));
    }
    
    
    /**
     * Test the exist() method with a session that does exist and is not expired
     * 
     * @throws Exception
     */
    @Test
    public void testExistsNotExpired () throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        long now = System.currentTimeMillis();
        //persist a session that is not expired
        SessionData data = store.newSessionData("1234", 100, now, now-1, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);
        
        store.start();

        assertTrue(store.exists("1234"));
    }
    
    /**
     * Test the exist() method with a session that does exist and is expired
     * 
     * @throws Exception
     */
    @Test
    public void testExistsIsExpired () throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        //persist a session that is expired
        SessionData data = store.newSessionData("1234", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP);
        persistSession(data);
        
        store.start();

        assertFalse(store.exists("1234"));
    }

    /**
     * Test the exist() method with a session that does not exist
     * 
     * @throws Exception
     */
     @Test
    public void testExistsNotExists () throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        store.start();

        assertFalse(store.exists("8888"));
    }
     
     
     @Test
     public void testExistsDifferentContext () throws Exception
     {
         //create the SessionDataStore
         ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
         context.setContextPath("/test");       
         SessionDataStoreFactory factory = createSessionDataStoreFactory();
         ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
         SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
         SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
         store.initialize(sessionContext);
         
         //persist a session for a different context
         SessionData data = store.newSessionData("1234", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
         data.setContextPath("_other");
         data.setLastNode(sessionContext.getWorkerName());
         persistSession(data);
         
         store.start();
         
         //check that session does not exist for this context
         assertFalse(store.exists("1234"));
     }
}
