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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AbstractSessionDataStoreTest
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
    public static final long RECENT_TIMESTAMP = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(3 * GRACE_PERIOD_SEC);

    protected URLClassLoader _contextClassLoader = new URLClassLoader(new URL[]{}, Thread.currentThread().getContextClassLoader());

    public abstract SessionDataStoreFactory createSessionDataStoreFactory();

    public abstract void persistSession(SessionData data) throws Exception;

    public abstract void persistUnreadableSession(SessionData data) throws Exception;

    public abstract boolean checkSessionExists(SessionData data) throws Exception;

    public abstract boolean checkSessionPersisted(SessionData data) throws Exception;

    /**
     * Test that the store can persist a session. The session uses an attribute
     * class that is only known to the webapp classloader. This tests that
     * we use the webapp loader when we serialize the session data (ie save the session).
     */
    @Test
    public void testStoreSession() throws Exception
    {
        //Use a class that would only be known to the webapp classloader
        InputStream foostream = Thread.currentThread().getContextClassLoader().getResourceAsStream("Foo.clazz");
        File foodir = new File(MavenTestingUtils.getTargetDir(), "foo");
        foodir.mkdirs();
        File fooclass = new File(foodir, "Foo.class");
        IO.copy(foostream, new FileOutputStream(fooclass));

        assertTrue(fooclass.exists());
        assertTrue(fooclass.length() != 0);

        URL[] foodirUrls = new URL[]{foodir.toURI().toURL()};
        _contextClassLoader = new URLClassLoader(foodirUrls, Thread.currentThread().getContextClassLoader());

        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        //use the classloader with the special class in it
        context.setClassLoader(_contextClassLoader);

        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        store.start();

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        SessionData data = null;
        try
        {
            Thread.currentThread().setContextClassLoader(_contextClassLoader);
            Class fooclazz = Class.forName("Foo", true, _contextClassLoader);
            //create a session
            long now = System.currentTimeMillis();
            data = store.newSessionData("1234", 100, now, now - 1, -1); //never expires
            data.setLastNode(sessionContext.getWorkerName());

            //Make an attribute that uses the class only known to the webapp classloader
            data.setAttribute("a", fooclazz.getConstructor(null).newInstance());
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }

        //store the session, using a different thread to ensure
        //that the thread is adorned with the webapp classloader
        //before serialization
        final SessionData finalData = data;

        Runnable r = new Runnable()
        {

            @Override
            public void run()
            {
                try
                {
                    store.store("1234", finalData);
                }
                catch (Exception e)
                {
                    fail(e);
                }
            }
        };

        Thread t = new Thread(r, "saver");
        t.start();
        t.join(TimeUnit.SECONDS.toMillis(10));

        //check that the store contains all of the session data
        assertTrue(checkSessionPersisted(data));
    }

    /**
     * Test that the store can update a pre-existing session.
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
        SessionData data = store.newSessionData("1234", 100, 200, 199, -1); //never expires
        data.setAttribute("a", "b");
        data.setLastNode(sessionContext.getWorkerName());
        data.setLastSaved(400); //make it look like it was previously saved by the store

        //put it into the store
        persistSession(data);

        //now test we can update the session
        data.setLastAccessed(now - 1);
        data.setAccessed(now);
        data.setMaxInactiveMs(TimeUnit.MINUTES.toMillis(2));
        data.setAttribute("a", "c");
        store.store("1234", data);

        assertTrue(checkSessionPersisted(data));
    }

    /**
     * Test that the store can persist a session that contains
     * serializable Proxy objects in the attributes.
     */
    @Test
    public void testStoreObjectAttributes() throws Exception
    {

        //Use classes that would only be known to the webapp classloader
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("Proxyable.clazz");
        File proxyabledir = new File(MavenTestingUtils.getTargetDir(), "proxyable");
        proxyabledir.mkdirs();
        File proxyableClass = new File(proxyabledir, "Proxyable.class");
        IO.copy(is, new FileOutputStream(proxyableClass));
        is.close();

        assertTrue(proxyableClass.exists());
        assertTrue(proxyableClass.length() != 0);

        is = Thread.currentThread().getContextClassLoader().getResourceAsStream("ProxyableInvocationHandler.clazz");
        File pihClass = new File(proxyabledir, "ProxyableInvocationHandler.class");
        IO.copy(is, new FileOutputStream(pihClass));
        is.close();

        is = Thread.currentThread().getContextClassLoader().getResourceAsStream("ProxyableFactory.clazz");
        File factoryClass = new File(proxyabledir, "ProxyableFactory.class");
        IO.copy(is, new FileOutputStream(factoryClass));
        is.close();

        is = Thread.currentThread().getContextClassLoader().getResourceAsStream("Foo.clazz");
        File fooClass = new File(proxyabledir, "Foo.class");
        IO.copy(is, new FileOutputStream(fooClass));
        is.close();

        URL[] proxyabledirUrls = new URL[]{proxyabledir.toURI().toURL()};
        _contextClassLoader = new URLClassLoader(proxyabledirUrls, Thread.currentThread().getContextClassLoader());

        //create the SessionDataStore 
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        //use the classloader with the special class in it
        context.setClassLoader(_contextClassLoader);

        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        store.start();

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        SessionData data = null;
        try
        {
            //Set the classloader and make a session containing a proxy as an attribute
            Thread.currentThread().setContextClassLoader(_contextClassLoader);
            Class factoryclazz = Class.forName("ProxyableFactory", true, _contextClassLoader);
            //create a session
            long now = System.currentTimeMillis();
            data = store.newSessionData("1234", 100, now, now - 1, -1); //never expires
            data.setLastNode(sessionContext.getWorkerName());
            Method m = factoryclazz.getMethod("newProxyable", ClassLoader.class);
            Object proxy = m.invoke(null, _contextClassLoader);

            //Make an attribute that uses the proxy only known to the webapp classloader
            data.setAttribute("a", proxy);

            //Now make an attribute that uses a system class to store a webapp classes
            //see issue #3597
            Class fooclazz = Class.forName("Foo", true, _contextClassLoader);
            Constructor constructor = fooclazz.getConstructor(null);
            Object foo = constructor.newInstance(null);
            ArrayList list = new ArrayList();
            list.add(foo);
            data.setAttribute("foo", list);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }

        //store the session, using a different thread to ensure
        //that the thread is adorned with the webapp classloader
        //before serialization
        final SessionData finalData = data;

        Runnable r = new Runnable()
        {

            @Override
            public void run()
            {
                try
                {
                    store.store("1234", finalData);
                }
                catch (Exception e)
                {
                    fail(e);
                }
            }
        };

        Thread t = new Thread(r, "saver");
        t.start();
        t.join(TimeUnit.SECONDS.toMillis(10));

        //check that the store contains all of the session data
        assertTrue(checkSessionPersisted(data));
    }

    /**
     * Test that we can load a persisted session.
     */
    @Test
    public void testLoadSessionExists() throws Exception
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
        SessionData data = store.newSessionData("1234", 100, now, now - 1, -1); //never expires
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);

        store.start();

        //test that we can retrieve it
        SessionData loaded = store.load("1234");
        assertNotNull(loaded);
        assertEquals("1234", loaded.getId());
        assertEquals(100, loaded.getCreated());
        assertEquals(now, loaded.getAccessed());
        assertEquals(now - 1, loaded.getLastAccessed());
        assertEquals(0, loaded.getExpiry());
    }

    /**
     * Test that an expired session can be loaded.
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
        SessionData data = store.newSessionData("678", 100, now - 20, now - 30, 10); //10 sec max idle
        data.setLastNode(sessionContext.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //make it expired recently
        persistSession(data);

        store.start();

        //test we can retrieve it
        SessionData loaded = store.load("678");
        assertNotNull(loaded);
        assertEquals("678", loaded.getId());
        assertEquals(100, loaded.getCreated());
        assertEquals(now - 20, loaded.getAccessed());
        assertEquals(now - 30, loaded.getLastAccessed());
        assertEquals(RECENT_TIMESTAMP, loaded.getExpiry());
    }

    /**
     * Test that a non-existent session cannot be loaded.
     */
    @Test
    public void testLoadSessionDoesNotExist() throws Exception
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
        SessionData data = store.newSessionData("222", 100, now, now - 1, -1);
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
     * Test that a session containing no attributes can be stored and re-read
     * @throws Exception
     */
    @Test
    public void testEmptyLoadSession() throws Exception
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
        
        //persist a session that has no attributes
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now - 1, -1);
        data.setLastNode(sessionContext.getWorkerName());
        //persistSession(data);
        store.store("222", data);

        //test that we can retrieve it
        SessionData savedSession = store.load("222");
        assertEquals(0, savedSession.getAllAttributes().size());
    }
    
    //Test that a session that had attributes can be modified to contain no
    //attributes, and still read
    @Test
    public void testModifyEmptyLoadSession() throws Exception
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
        
        //persist a session that has attributes
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now - 1, -1);
        data.setAttribute("foo", "bar");
        data.setLastNode(sessionContext.getWorkerName());
        store.store("222", data);

        //test that we can retrieve it
        SessionData savedSession = store.load("222");
        assertEquals("bar", savedSession.getAttribute("foo"));
        
        //now modify so there are no attributes
        savedSession.setAttribute("foo", null);
        store.store("222", savedSession);
        
        //check its still readable
        savedSession = store.load("222");
        assertEquals(0, savedSession.getAllAttributes().size());
    }
    
    /**
     * Test that we can delete a persisted session.
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
        SessionData data = store.newSessionData("1234", 100, now, now - 1, -1);
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

        Set<String> candidates = new HashSet<>(Arrays.asList("1234", "5678"));
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("1234", "5678"));
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates are not expired in the store.
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
        SessionData data = store.newSessionData("1234", 100, now, now - 1, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);

        //persist another session that is not expired
        SessionData data2 = store.newSessionData("5678", 100, now, now - 1, TimeUnit.MINUTES.toMillis(60));
        data2.setLastNode(sessionContext.getWorkerName());
        persistSession(data2);

        store.start();

        Set<String> candidates = new HashSet<>(Arrays.asList("1234", "5678"));
        Set<String> expiredIds = store.getExpired(candidates);
        assertEquals(0, expiredIds.size());
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates don't exist in the store.
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

        Set<String> candidates = new HashSet<>(Arrays.asList("1234", "5678"));
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("1234", "5678"));
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * there are more persisted expired sessions in the store than
     * present in the candidate list.
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
        assertThat(expiredIds, containsInAnyOrder("1234", "5678"));
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * there are sessions that are not in use on the node, but have
     * expired and are last used by another node.
     */
    @Test
    public void testGetExpiredDifferentNode() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        //persist a session that is expired for a different node
        SessionData data = store.newSessionData("1234", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode("other");
        data.setExpiry(RECENT_TIMESTAMP); //must be recently expired, or FileSessionDataStore will eliminate it on startup
        persistSession(data);

        store.start();

        Set<String> candidates = new HashSet<>();
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("1234"));
    }

    /**
     * Test the exist() method with a session that does exist and is not expired
     */
    @Test
    public void testExistsNotExpired() throws Exception
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
        SessionData data = store.newSessionData("1234", 100, now, now - 1, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        persistSession(data);

        store.start();

        assertTrue(store.exists("1234"));
    }

    /**
     * Test the exist() method with a session that does exist and is expired
     */
    @Test
    public void testExistsIsExpired() throws Exception
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
     */
    @Test
    public void testExistsNotExists() throws Exception
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
    public void testExistsDifferentContext() throws Exception
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

    /**
     * Test setting a save period to avoid writes when the attributes haven't changed.
     */
    @Test
    public void testSavePeriodOnUpdate()
        throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        ((AbstractSessionDataStoreFactory)factory).setSavePeriodSec(20); //only save every 20sec
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        store.start();

        long now = System.currentTimeMillis();

        //persist a session that is not expired, and has been saved before
        SessionData data = store.newSessionData("1234", 100, now - 10, now - 20, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        data.setLastSaved(now - 100);
        persistSession(data);

        //update just the access and last access time
        data.setLastAccessed(now - 5);
        data.setAccessed(now - 1);

        //test that a save does not change the stored data
        store.store("1234", data);

        //reset the times for a check
        data.setLastAccessed(now - 20);
        data.setAccessed(now - 10);
        checkSessionPersisted(data);
    }

    /**
     * Check that a session that has never previously been
     * saved will be saved despite the savePeriod setting.
     */
    @Test
    public void testSavePeriodOnCreate()
        throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        ((AbstractSessionDataStoreFactory)factory).setSavePeriodSec(20); //only save every 20sec
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        store.start();

        long now = System.currentTimeMillis();
        //create a session that is not expired, and has never been saved before
        SessionData data = store.newSessionData("1234", 100, now - 10, now - 20, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());

        store.store("1234", data);

        checkSessionPersisted(data);
    }

    /**
     * Check that a session whose attributes have changed will always
     * be saved despite the savePeriod
     */
    @Test
    public void testSavePeriodDirtySession()
        throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        ((AbstractSessionDataStoreFactory)factory).setSavePeriodSec(20); //only save every 20sec
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        store.start();

        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", 100, now - 10, now - 20, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(sessionContext.getWorkerName());
        data.setLastSaved(now - 100);
        data.setAttribute("wibble", "wobble");
        persistSession(data);

        //now change the attributes
        data.setAttribute("wibble", "bobble");
        data.setLastAccessed(now - 5);
        data.setAccessed(now - 1);

        store.store("1234", data);

        checkSessionPersisted(data);
    }
}
