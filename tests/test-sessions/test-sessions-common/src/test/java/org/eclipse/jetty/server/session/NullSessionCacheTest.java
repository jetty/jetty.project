//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionEvent;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NullSessionCacheTest
 */
public class NullSessionCacheTest
{
    public static class SerializableTestObject implements Serializable, HttpSessionActivationListener
    {
        int count;
        static int passivates = 0;
        static int activates = 0;
        
        public SerializableTestObject(int i)
        {
            count = i;
        }
        
        @Override
        public void sessionWillPassivate(HttpSessionEvent se)
        {
            //should never be called, as we are replaced with the
            //non-serializable object and thus passivate will be called on that
            ++passivates;
        }

        @Override
        public void sessionDidActivate(HttpSessionEvent se)
        {
            ++activates;
            //remove myself, replace with something serializable
            se.getSession().setAttribute("pv", new TestObject(count));
        }
    }
    
    
    
    public static class TestObject implements HttpSessionActivationListener
    {
        int i;
        static int passivates = 0;
        static int activates = 0;
        
        public TestObject(int j)
        {
            i = j;
        }

        @Override
        public void sessionWillPassivate(HttpSessionEvent se)
        {
            ++passivates;
            //remove myself, replace with something serializable
            se.getSession().setAttribute("pv", new SerializableTestObject(i));
        }

        @Override
        public void sessionDidActivate(HttpSessionEvent se)
        {
            //this should never be called because we replace ourselves during passivation,
            //so it is the SerializableTestObject that is activated instead
            ++activates;
        }
    }
    
    
    @Test
    public void testWritesWithPassivation() throws Exception
    {
        //Test that a session that is in the process of being saved cannot cause
        //another save via a passivation listener
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setWriteThroughMode(NullSessionCache.WriteThroughMode.ALWAYS);
        
        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore(true); //pretend to passivate
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        
        context.start();
        
        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(null, data); //mimic a request making a session
        cache.add("1234", session);
        //at this point the session should not be saved to the store
        assertEquals(0, store._numSaves.get());
        
        //set an attribute that is not serializable, should cause a save
        TestObject obj = new TestObject(1);
        session.setAttribute("pv", obj);
        assertTrue(cache._listener._sessionsBeingWritten.isEmpty());
        assertTrue(store.exists("1234"));
        assertEquals(1, store._numSaves.get());
        assertEquals(1, TestObject.passivates);
        assertEquals(0, TestObject.activates);
        assertEquals(1, SerializableTestObject.activates);
        assertEquals(0, SerializableTestObject.passivates);
    }
    @Test
    public void testChangeWriteThroughMode() throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();

        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        
        assertEquals(NullSessionCache.WriteThroughMode.ON_EXIT, cache.getWriteThroughMode());
        assertNull(cache._listener);
        
        //change mode to NEW
        cache.setWriteThroughMode(NullSessionCache.WriteThroughMode.NEW);
        assertEquals(NullSessionCache.WriteThroughMode.NEW, cache.getWriteThroughMode());
        assertNotNull(cache._listener);
        assertEquals(1, context.getSessionHandler()._sessionAttributeListeners.size());
        assertTrue(context.getSessionHandler()._sessionAttributeListeners.contains(cache._listener));

        
        //change mode to ALWAYS from NEW, listener should remain
        NullSessionCache.WriteThroughAttributeListener old = cache._listener;
        cache.setWriteThroughMode(NullSessionCache.WriteThroughMode.ALWAYS);
        assertEquals(NullSessionCache.WriteThroughMode.ALWAYS, cache.getWriteThroughMode());
        assertNotNull(cache._listener);
        assertTrue(old == cache._listener);
        assertEquals(1, context.getSessionHandler()._sessionAttributeListeners.size());
        
        //check null is same as ON_EXIT
        cache.setWriteThroughMode(null);
        assertEquals(NullSessionCache.WriteThroughMode.ON_EXIT, cache.getWriteThroughMode());
        assertNull(cache._listener);
        assertEquals(0, context.getSessionHandler()._sessionAttributeListeners.size());
        
        //change to ON_EXIT
        cache.setWriteThroughMode(NullSessionCache.WriteThroughMode.ON_EXIT);
        assertEquals(NullSessionCache.WriteThroughMode.ON_EXIT, cache.getWriteThroughMode());
        assertNull(cache._listener);
        assertEquals(0, context.getSessionHandler()._sessionAttributeListeners.size());
    }
    
    
    
    @Test
    public void testWriteThroughAlways() throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setWriteThroughMode(NullSessionCache.WriteThroughMode.ALWAYS);

        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(null, data); //mimic a request making a session
        cache.add("1234", session);
        //at this point the session should not be saved to the store
        assertEquals(0, store._numSaves.get());
        
        //check each call to set attribute results in a store
        session.setAttribute("colour", "blue");
        assertTrue(store.exists("1234"));
        assertEquals(1, store._numSaves.get());
        
        //mimic releasing the session after the request is finished
        cache.release("1234", session);
        assertTrue(store.exists("1234"));
        assertFalse(cache.contains("1234"));

        //simulate a new request using the previously created session
        //the session should not now be new
        session = cache.get("1234"); //get the session again
        session.access(now); //simulate a request
        session.setAttribute("spin", "left");
        assertTrue(store.exists("1234"));
        assertEquals(2, store._numSaves.get());
        cache.release("1234", session); //finish with the session

        assertFalse(session.isResident());
    }
    
    @Test
    public void testWriteThroughNew () throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        cacheFactory.setWriteThroughMode(NullSessionCache.WriteThroughMode.NEW);

        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(null, data); //mimic a request making a session
        cache.add("1234", session);
        //at this point the session should not be saved to the store
        assertEquals(0, store._numSaves.get());
        assertTrue(session.isNew());
        
        //check each call to set attribute results in a store while the session is new
        session.setAttribute("colour", "blue");
        assertTrue(store.exists("1234"));
        assertEquals(1, store._numSaves.get());
        session.setAttribute("charge", "positive");
        assertEquals(2, store._numSaves.get());
        
        //mimic releasing the session after the request is finished
        cache.release("1234", session);
        assertTrue(store.exists("1234"));
        assertFalse(cache.contains("1234"));
        assertEquals(2, store._numSaves.get());


        //simulate a new request using the previously created session
        //the session should not now be new, so setAttribute should
        //not result in a save
        session = cache.get("1234"); //get the session again
        session.access(now); //simulate a request
        assertFalse(session.isNew());
        assertEquals(2, store._numSaves.get());
        session.setAttribute("spin", "left");
        assertTrue(store.exists("1234"));
        assertEquals(2, store._numSaves.get());
        session.setAttribute("flavor", "charm");
        assertEquals(2, store._numSaves.get());
        cache.release("1234", session); //finish with the session
        assertEquals(3, store._numSaves.get());//release session should write it out
        assertFalse(session.isResident());  
    }
    
    
    @Test
    public void testNotCached() throws Exception
    {
        //Test the NullSessionCache never contains the session
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        context.setServer(server);

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();

        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();

        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now - 20, now - 10, now - 20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now + TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(null, data); //mimic a request making a session
        cache.add("1234", session); 
        assertFalse(cache.contains("1234"));//null cache doesn't actually store the session
        
        //mimic releasing the session after the request is finished
        cache.release("1234", session);
        assertTrue(store.exists("1234"));
        assertFalse(cache.contains("1234"));

        //simulate a new request using the previously created session
        session = cache.get("1234"); //get the session again
        session.access(now); //simulate a request
        cache.release("1234", session); //finish with the session
        assertFalse(cache.contains("1234"));

        assertFalse(session.isResident());
    }
}
