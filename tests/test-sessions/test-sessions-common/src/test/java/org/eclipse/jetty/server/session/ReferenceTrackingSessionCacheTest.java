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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Test;

/**
 * ReferenceTrackingSessionCacheTest
 *
 *
 */
public class ReferenceTrackingSessionCacheTest
{
    public class MyHttpSessionIdListener implements HttpSessionIdListener
    {
        List<Session> _called = new ArrayList<>();
        
        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
           _called.add((Session)event.getSession());
        }
        
        public List<Session> getCalled()
        {
            return _called;
        }
    }
    
    
    
    
    public class MyHttpSessionListener implements HttpSessionListener
    {
        List<Session> _createdCalled = new ArrayList<>();
        List<Session> _destroyedCalled = new ArrayList<>();
        
        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
           _createdCalled.add((Session)se.getSession());
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            _destroyedCalled.add((Session)se.getSession());
        }
        
        public List<Session> getCreatedCalled()
        {
            return _createdCalled;
        }       

        public List<Session> getDestroyedCalled()
        {
            return _destroyedCalled;
        }
        
        
        public void clear()
        {
            _createdCalled.clear();
            _destroyedCalled.clear();
        }
    }
    
    
    
    @Test
    public void testInvalidation() throws Exception
    {
        //Test that invalidation happens on ALL copies of the session that are in-use by requests
        Server server = new Server();

        SessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.setSessionIdManager(sessionIdManager);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
        context.setContextPath("/test");
        context.setServer(server);
        context.getSessionHandler().setMaxInactiveInterval((int)TimeUnit.DAYS.toSeconds(1));
        context.getSessionHandler().setSessionIdManager(sessionIdManager);

        ReferenceTrackingSessionCacheFactory cacheFactory = new ReferenceTrackingSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true); //ensures that a session is persisted as soon as it is created
        
        ReferenceTrackingSessionCache cache = (ReferenceTrackingSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        MyHttpSessionListener listener = new MyHttpSessionListener();
        context.getSessionHandler().addEventListener(listener);

        server.setHandler(context);
        try
        {
            server.start();

            //test creating a new session
            Session session = (Session)context.getSessionHandler().newHttpSession(null);
            String id = session.getId();
            context.getSessionHandler().access(session, false); //simulate accessing the request
            assertEquals(1, cache.references(id)); //the simulated request is referencing the session
            context.getSessionHandler().complete(session); //simulate completing the request
            assertEquals(0, cache.references(id)); //no references stored
            List<Session> called = listener.getCreatedCalled();
            assertNotNull(called);
            assertFalse(called.isEmpty());
            assertEquals(1, called.size());
            
            //zero out listener calls
            listener.clear();
            
            //make 1st request
            session = context.getSessionHandler().getSession(id); //get the session again
            assertNotNull(session);
            context.getSessionHandler().access(session, false); //simulate accessing the request
            assertEquals(1, cache.references(id)); //the simulated request is referencing the session

            System.err.println("-------------------------");
            
            //make 2nd request and invalidate
            Session session2 = context.getSessionHandler().getSession(id); //get the session again
            context.getSessionHandler().access(session2, false); //simulate accessing the request
            assertNotNull(session2);
            assertTrue(session != session2);
            assertEquals(session.getId(), session2.getId());
            assertEquals(2, cache.references(id)); //another request means another reference
            
            session2.invalidate();
            assertEquals(0, cache.references(id)); //no refs for id

            called = listener.getDestroyedCalled();
            assertNotNull(called);
            assertFalse(called.isEmpty());
//            assertEquals(2, called.size());
            assertTrue(called.contains(session));
            assertTrue(called.contains(session2));
            
            try
            {
                session.invalidate();
                fail("Should already be invalid");
            }
            catch (IllegalStateException e)
            {
                //Expect it to be already invalid
            }            
            try
            {
                session2.invalidate();
                fail("Should already be invalid");
            }
            catch (IllegalStateException e)
            {
                //Expect it to be already invalid
            }
        }
        finally
        {
            server.stop();
        }
    }
    
    
    @Test
    public void testSessionIdRenewalMultipleRequests() throws Exception
    {
        //Test that the id is changed on ALL copies of the session that are in-use by requests
        Server server = new Server();

        SessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.setSessionIdManager(sessionIdManager);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
        context.setContextPath("/test");
        context.setServer(server);
        context.getSessionHandler().setMaxInactiveInterval((int)TimeUnit.DAYS.toSeconds(1));
        context.getSessionHandler().setSessionIdManager(sessionIdManager);

        ReferenceTrackingSessionCacheFactory cacheFactory = new ReferenceTrackingSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true); //ensures that a session is persisted as soon as it is created
        
        ReferenceTrackingSessionCache cache = (ReferenceTrackingSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        MyHttpSessionIdListener listener = new MyHttpSessionIdListener();
        context.getSessionHandler().addEventListener(listener);

        server.setHandler(context);
        try
        {
            server.start();

            //test creating a new session
            Session session = (Session)context.getSessionHandler().newHttpSession(null);
            String id = session.getId();
            context.getSessionHandler().access(session, false); //simulate accessing the request
            assertEquals(1, cache.references(id)); //the simulated request is referencing the session
            context.getSessionHandler().complete(session); //simulate completing the request
            assertEquals(0, cache.references(id)); //no references stored

            //make 1 request
            session = context.getSessionHandler().getSession(id); //get the session again
            assertNotNull(session);
            context.getSessionHandler().access(session, false); //simulate accessing the request
            assertEquals(1, cache.references(id)); //the simulated request is referencing the session

            String originalId = session.getId();

            System.err.println("-------------------------");
            
            //make 2nd request and change the id
            Session session2 = context.getSessionHandler().getSession(id); //get the session again\
            context.getSessionHandler().access(session2, false); //simulate accessing the request
            assertNotNull(session2);
            assertTrue(session != session2);
            assertEquals(session.getId(), session2.getId());
            assertEquals(2, cache.references(originalId)); //another request means another reference
            
            session2.renewId(new Request(null, null));
            assertEquals(0, cache.references(originalId)); //no refs for old id
            assertEquals(2, cache.references(session2.getId())); //still 2 refs for new id
            System.err.println("old="+originalId+" session1="+session.getId()+" session2="+session2.getId());

            assertFalse(originalId.equals(session.getId()));
            assertFalse(originalId.equals(session2.getId()));
            assertEquals(session.getId(), session2.getId());
            List<Session> called = listener.getCalled();
            assertNotNull(called);
            assertFalse(called.isEmpty());
            assertEquals(2, called.size());
            assertTrue(called.contains(session));
            assertTrue(called.contains(session2));
        }
        finally
        {
            server.stop();
        }
    }


    @Test
    public void testEvictOnExit() throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
        context.setContextPath("/test");
        context.setServer(server);
        context.getSessionHandler().setMaxInactiveInterval((int)TimeUnit.DAYS.toSeconds(1));

        ReferenceTrackingSessionCacheFactory cacheFactory = new ReferenceTrackingSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true); //ensures that a session is persisted as soon as it is created
        
        ReferenceTrackingSessionCache cache = (ReferenceTrackingSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();
        
        //test creating a new session
        Session session = (Session)context.getSessionHandler().newHttpSession(null);
        String id = session.getId();
        context.getSessionHandler().access(session, false); //simulate accessing the request
        assertEquals(1, cache.references(id)); //the simulated request is referencing the session
        context.getSessionHandler().complete(session); //simulate completing the request
        assertFalse(cache.contains(id)); //should not be in the cache
        assertEquals(0, cache.references(id)); //no references stored
        assertTrue(store.exists(id)); //but should be in the store
        
        //test retrieving the session
         session = context.getSessionHandler().getSession(id); //get the session again
        assertNotNull(session);
        context.getSessionHandler().access(session, false); //simulate accessing the request
        assertEquals(1, cache.references(id)); //the simulated request is referencing the session
        context.getSessionHandler().complete(session); //simulate completing the request
        assertEquals(0, cache.references(id)); //no reference after request exits
        assertFalse(cache.contains(id));
        assertTrue(store.exists(id));
        assertFalse(session.isResident());
        assertTrue(session.isValid());
        
        //test creating multiple requests creates multiple sessions
        Session session1 = (Session)context.getSessionHandler().newHttpSession(null);
        String id1 = session1.getId();
        context.getSessionHandler().access(session1, false); //simulate accessing the request
        assertEquals(1, cache.references(id1)); //the simulated request is referencing the session
        
        Session session2 = context.getSessionHandler().getSession(id1); //get the session again
        assertNotNull(session2);
        context.getSessionHandler().access(session2, false); //simulate accessing the request
        assertEquals(2, cache.references(id1)); //another request means another reference
        assertFalse(session1 == session2); //should be different objects
        
        context.getSessionHandler().complete(session2); //simulate 2nd request complete
        assertEquals(1, cache.references(id1)); //only 1 request left
        context.getSessionHandler().complete(session1); //complete 1st request
        assertEquals(0, cache.references(id1));
    }

}
