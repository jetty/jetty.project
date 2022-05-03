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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.BeforeAll;
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
    protected static File extraClasses;

    protected URLClassLoader _contextClassLoader;
    protected Server _server;
    protected TestableSessionManager _sessionManager;
    protected DefaultSessionIdManager _sessionIdManager;
    protected SessionDataStoreFactory _factory;
    
    public abstract SessionDataStoreFactory createSessionDataStoreFactory();

    public abstract void persistSession(SessionData data) throws Exception;

    public abstract void persistUnreadableSession(SessionData data) throws Exception;

    public abstract boolean checkSessionExists(SessionData data) throws Exception;

    public abstract boolean checkSessionPersisted(SessionData data) throws Exception;
    
    public class TestableContextHandler extends ContextHandler
    {
        SessionManager _sessionManager;

        public SessionManager getSessionManager()
        {
            return _sessionManager;
        }

        public void setSessionManager(SessionManager sessionManager)
        {
            SessionManager tmp = _sessionManager;
            _sessionManager = sessionManager;
            updateBean(tmp, sessionManager);
        } 
    }

    /**
     * Cannot be a BeforeEach, because this 
     * BeforeEach is executed before the subclass one, but it
     * one relies on BeforeEach behaviour in the subclass!
     */
    public void setUp()
    {
        _server = new Server();
        
        //Make fake context to satisfy the SessionHandler
        TestableContextHandler contextHandler = new TestableContextHandler();
        contextHandler.setClassLoader(_contextClassLoader);
        contextHandler.setServer(_server);
        _server.setHandler(contextHandler);

        //create the SessionDataStore  
        _sessionIdManager = new DefaultSessionIdManager(_server);
        _server.addBean(_sessionIdManager, true);

        _sessionManager = new TestableSessionManager();
        _sessionManager.setSessionIdManager(_sessionIdManager);
        _sessionManager.setServer(_server);
        contextHandler.setSessionManager(_sessionManager);
        _factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)_factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        _server.addBean(_factory, true);
    }
    
    @BeforeAll
    public static void beforeAll()
        throws Exception
    {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream("Foo.clazz");
        extraClasses = new File(MavenTestingUtils.getTargetDir(), "extraClasses");
        extraClasses.mkdirs();
        File fooclass = new File(extraClasses, "Foo.class");
        IO.copy(is, new FileOutputStream(fooclass));
        is.close();

        is = Thread.currentThread().getContextClassLoader().getResourceAsStream("Proxyable.clazz");
        File proxyableClass = new File(extraClasses, "Proxyable.class");
        IO.copy(is, new FileOutputStream(proxyableClass));
        is.close();

        is = Thread.currentThread().getContextClassLoader().getResourceAsStream("ProxyableInvocationHandler.clazz");
        File pihClass = new File(extraClasses, "ProxyableInvocationHandler.class");
        IO.copy(is, new FileOutputStream(pihClass));
        is.close();

        is = Thread.currentThread().getContextClassLoader().getResourceAsStream("ProxyableFactory.clazz");
        File factoryClass = new File(extraClasses, "ProxyableFactory.class");
        IO.copy(is, new FileOutputStream(factoryClass));
        is.close();

    }
    
    public AbstractSessionDataStoreTest() throws Exception
    {
        URL[] foodirUrls = new URL[]{extraClasses.toURI().toURL()};
        _contextClassLoader = new URLClassLoader(foodirUrls, Thread.currentThread().getContextClassLoader());
    }
    
    /**
     * Test that the store can persist a session. The session uses an attribute
     * class that is only known to the webapp classloader. This tests that
     * we use the webapp loader when we serialize the session data (ie save the session).
     */
    @Test
    public void testStoreSession() throws Exception
    {
        setUp();
        _server.start();
        
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        SessionData data = null;
        try
        {
            Thread.currentThread().setContextClassLoader(_contextClassLoader);
            Class fooclazz = Class.forName("Foo", true, _contextClassLoader);
            //create a session
            long now = System.currentTimeMillis();
            data = store.newSessionData("aaa1", 100, now, now - 1, -1); //never expires
            data.setLastNode(_sessionIdManager.getWorkerName());

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
                    store.store("aaa1", finalData);
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
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //create a session
        final long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa2", 100, 200, 199, -1); //never expires
        data.setAttribute("a", "b");
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setLastSaved(400); //make it look like it was previously saved by the store

        //put it into the store
        persistSession(data);
        
        assertTrue(checkSessionExists(data));
        
        //now test we can update the session
        data.setLastAccessed(now - 1);
        data.setAccessed(now);
        data.setMaxInactiveMs(TimeUnit.MINUTES.toMillis(2));
        data.setAttribute("a", "c");
        
        store.store("aaa2", data);
        assertTrue(checkSessionPersisted(data));
    }

    /**
     * Test that the store can persist a session that contains
     * serializable Proxy objects in the attributes.
     */
    @Test
    public void testStoreObjectAttributes() throws Exception
    {
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        SessionData data = null;
        try
        {
            //Set the classloader and make a session containing a proxy as an attribute
            Thread.currentThread().setContextClassLoader(_contextClassLoader);
            Class factoryclazz = Class.forName("ProxyableFactory", true, _contextClassLoader);
            //create a session
            long now = System.currentTimeMillis();
            data = store.newSessionData("aaa3", 100, now, now - 1, -1); //never expires
            data.setLastNode(_sessionIdManager.getWorkerName());
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
                    store.store("aaa3", finalData);
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
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa4", 100, now, now - 1, -1); //never expires
        data.setLastNode(_sessionIdManager.getWorkerName());
        persistSession(data);
        _server.stop();
        _server.start(); //reindex the session files on disk
        
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //test that we can retrieve it
        SessionData loaded = store.load("aaa4");
        assertNotNull(loaded);
        assertEquals("aaa4", loaded.getId());
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
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        //persist a session that is expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa5", 100, now - 20, now - 30, 10); //10 sec max idle
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //make it expired recently
        persistSession(data);
        
        _server.stop();
        _server.start();

        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //test we can retrieve it
        SessionData loaded = store.load("aaa5");
        assertNotNull(loaded);
        assertEquals("aaa5", loaded.getId());
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
        setUp();
        _server.start();
        
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

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
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that is damaged and cannot be read
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("222", 100, now, now - 1, -1);
        data.setLastNode(_sessionIdManager.getWorkerName());
        persistUnreadableSession(data);
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
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
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //persist a session that has no attributes
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa6", 100, now, now - 1, -1);
        data.setLastNode(_sessionIdManager.getWorkerName());
        //persistSession(data);
        store.store("aaa6", data);
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //test that we can retrieve it
        SessionData savedSession = store.load("aaa6");
        assertEquals(0, savedSession.getAllAttributes().size());
    }
    
    //Test that a session that had attributes can be modified to contain no
    //attributes, and still read
    @Test
    public void testModifyEmptyLoadSession() throws Exception
    {
        setUp();
        _server.start();
        
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //persist a session that has attributes
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa7", 100, now, now - 1, -1);
        data.setAttribute("foo", "bar");
        data.setLastNode(_sessionIdManager.getWorkerName());
        store.store("aaa7", data);

        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //test that we can retrieve it
        SessionData savedSession = store.load("aaa7");
        assertEquals("bar", savedSession.getAttribute("foo"));
        
        //now modify so there are no attributes
        savedSession.setAttribute("foo", null);
        store.store("aaa7", savedSession);
        
        //check its still readable
        savedSession = store.load("aaa7");
        assertEquals(0, savedSession.getAllAttributes().size());
    }
    
    /**
     * Test that we can delete a persisted session.
     */
    @Test
    public void testDeleteSessionExists() throws Exception
    {
        setUp();
        _server.start();
        
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa8", 100, now, now - 1, -1);
        data.setLastNode(_sessionIdManager.getWorkerName());
        persistSession(data);
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        //delete the session via the store
        store.delete("aaa8");

        //check the session is no longer exists
        assertFalse(checkSessionExists(data));
    }

    /**
     * Test deletion of non-existent session.
     */
    @Test
    public void testDeleteSessionDoesNotExist() throws Exception
    {
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

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
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that is expired
        SessionData data = store.newSessionData("aaa9", 100, 101, 101, 10);
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //make it expired recently so FileSessionDataStore doesn't eliminate it on startup
        persistSession(data);

        //persist another session that is expired
        SessionData data2 = store.newSessionData("aaa10", 100, 100, 101, 30);
        data2.setLastNode(_sessionIdManager.getWorkerName());
        data2.setExpiry(RECENT_TIMESTAMP); //make it expired recently so FileSessionDataStore doesn't eliminate it on startup
        persistSession(data2);
        
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();

        Set<String> candidates = new HashSet<>(Arrays.asList(new String[]{"aaa9", "aaa10"}));
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("aaa9", "aaa10"));
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * the session candidates are not expired in the store.
     */
    @Test
    public void testGetExpiredPersistedNotExpired() throws Exception
    {
        setUp();
        _server.start();
        
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        long now = System.currentTimeMillis();
        //persist a session that is not expired
        SessionData data = store.newSessionData("aaa11", 100, now, now - 1, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());
        persistSession(data);

        //persist another session that is not expired
        SessionData data2 = store.newSessionData("aaa12", 100, now, now - 1, TimeUnit.MINUTES.toMillis(60));
        data2.setLastNode(_sessionIdManager.getWorkerName());
        persistSession(data2);
        
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();

        Set<String> candidates = new HashSet<>(Arrays.asList(new String[]{"aaa11", "aaa12"}));
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
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        Set<String> candidates = new HashSet<>(Arrays.asList(new String[]{"a", "b"}));
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("a", "b"));
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * there are more persisted expired sessions in the store than
     * present in the candidate list.
     */
    @Test
    public void testGetExpiredPersistedAndExpiredOnly() throws Exception
    {
        setUp();
        _server.start();

        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();
        //persist a session that is expired
        SessionData data = store.newSessionData("aaa13", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP); //must be recently expired, or FileSessionDataStore will eliminate it on startup
        persistSession(data);

        //persist another session that is expired
        SessionData data2 = store.newSessionData("aaa14", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data2.setLastNode(_sessionIdManager.getWorkerName());
        data2.setExpiry(RECENT_TIMESTAMP); //must be recently expired, or FileSessionDataStore will eliminate it on startup
        persistSession(data2);
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        Set<String> candidates = new HashSet<>();
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("aaa13", "aaa14"));
    }

    /**
     * Test SessionDataStore.getExpired: tests the situation where
     * there are sessions that are not in use on the node, but have
     * expired and are last used by another node.
     */
    @Test
    public void testGetExpiredDifferentNode() throws Exception
    {
        setUp();
        _server.start();
        
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that is expired for a different node
        SessionData data = store.newSessionData("aaa15", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode("other");
        data.setExpiry(RECENT_TIMESTAMP); //must be recently expired, or FileSessionDataStore will eliminate it on startup
        persistSession(data);

        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        Set<String> candidates = new HashSet<>();
        Set<String> expiredIds = store.getExpired(candidates);
        assertThat(expiredIds, containsInAnyOrder("aaa15"));
    }
    
    @Test
    public void testCleanOrphans() throws Exception
    {
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        long now = System.currentTimeMillis();
        
        //persist a long ago expired session for our context
        SessionData oldSession = store.newSessionData("001", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        oldSession.setExpiry(200);
        oldSession.setLastNode("me");
        persistSession(oldSession);
        assertTrue(checkSessionExists(oldSession));
        
        //persist a recently expired session for our context
        SessionData expiredSession = store.newSessionData("002", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        expiredSession.setExpiry(RECENT_TIMESTAMP);
        expiredSession.setLastNode("me");
        persistSession(expiredSession);
        assertTrue(checkSessionExists(expiredSession));

        //persist a non expired session for our context
        SessionData unexpiredSession = store.newSessionData("003", 100, now + 10, now + 5, TimeUnit.MINUTES.toMillis(60));
        unexpiredSession.setExpiry(now + TimeUnit.MINUTES.toMillis(10));
        unexpiredSession.setLastNode("me");
        persistSession(unexpiredSession);
        assertTrue(checkSessionExists(unexpiredSession));

        //persist an immortal session for our context
        SessionData immortalSession = store.newSessionData("004", 100, now + 10, now + 5, TimeUnit.MINUTES.toMillis(60));
        immortalSession.setExpiry(0);
        immortalSession.setLastNode("me");
        persistSession(immortalSession);
        assertTrue(checkSessionExists(immortalSession));
        
        //create sessions for a different context
        //persist a long ago expired session for a different context
        SessionData oldForeignSession = store.newSessionData("005", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        oldForeignSession.setContextPath("_other");
        oldForeignSession.setExpiry(200);
        oldForeignSession.setLastNode("me");
        persistSession(oldForeignSession);
        assertTrue(checkSessionExists(oldForeignSession));
        
        //persist a recently expired session for our context
        SessionData expiredForeignSession = store.newSessionData("006", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        expiredForeignSession.setContextPath("_other");
        expiredForeignSession.setExpiry(RECENT_TIMESTAMP);
        expiredForeignSession.setLastNode("me");
        persistSession(expiredForeignSession);
        assertTrue(checkSessionExists(expiredForeignSession));
        
        //persist a non expired session for our context
        SessionData unexpiredForeignSession = store.newSessionData("007", 100, now + 10, now + 5, TimeUnit.MINUTES.toMillis(60));
        unexpiredForeignSession.setContextPath("_other");
        unexpiredForeignSession.setExpiry(now + TimeUnit.MINUTES.toMillis(10));
        unexpiredForeignSession.setLastNode("me");
        persistSession(unexpiredForeignSession);
        assertTrue(checkSessionExists(unexpiredForeignSession));
        
        //persist an immortal session for our context
        SessionData immortalForeignSession = store.newSessionData("008", 100, now + 10, now + 5, TimeUnit.MINUTES.toMillis(60));
        immortalForeignSession.setContextPath("_other");
        immortalForeignSession.setExpiry(0);
        immortalForeignSession.setLastNode("me");
        persistSession(immortalForeignSession);
        assertTrue(checkSessionExists(immortalForeignSession));
        
        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();
        
        ((AbstractSessionDataStore)store).cleanOrphans(now - TimeUnit.SECONDS.toMillis(10 * GRACE_PERIOD_SEC));

        //old session should be gone
        assertFalse(checkSessionExists(oldSession));
        //recently expired session should still be there
        assertTrue(checkSessionExists(expiredSession));
        //unexpired session should still be there
        assertTrue(checkSessionExists(unexpiredSession));
        //immortal session should still exist
        assertTrue(checkSessionExists(immortalSession));
        //old foreign session should be gone
        assertFalse(checkSessionExists(oldSession));
        //recently expired foreign session should still be there
        assertTrue(checkSessionExists(expiredSession));
        //unexpired foreign session should still be there
        assertTrue(checkSessionExists(unexpiredSession));
        //immortal foreign session should still exist
        assertTrue(checkSessionExists(immortalSession));
    }

    /**
     * Test the exist() method with a session that does exist and is not expired
     */
    @Test
    public void testExistsNotExpired() throws Exception
    {
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        long now = System.currentTimeMillis();
        //persist a session that is not expired
        SessionData data = store.newSessionData("aaa16", 100, now, now - 1, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());
        persistSession(data);

        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();

        assertTrue(store.exists("aaa16"));
    }

    /**
     * Test the exist() method with a session that does exist and is expired
     */
    @Test
    public void testExistsIsExpired() throws Exception
    {
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that is expired
        SessionData data = store.newSessionData("aaa17", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setExpiry(RECENT_TIMESTAMP);
        persistSession(data);

        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();

        assertFalse(store.exists("aaa17"));
    }

    /**
     * Test the exist() method with a session that does not exist
     */
    @Test
    public void testExistsNotExists() throws Exception
    {
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        assertFalse(store.exists("8888"));
    }

    @Test
    public void testExistsDifferentContext() throws Exception
    {
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session for a different context
        SessionData data = store.newSessionData("aaa18", 100, 101, 100, TimeUnit.MINUTES.toMillis(60));
        data.setContextPath("_other");
        data.setLastNode(_sessionIdManager.getWorkerName());
        persistSession(data);

        _server.stop();
        _server.start(); //reindex files
        store = _sessionManager.getSessionCache().getSessionDataStore();

        //check that session does not exist for this context
        assertFalse(store.exists("aaa18"));
    }

    /**
     * Test setting a save period to avoid writes when the attributes haven't changed.
     */
    @Test
    public void testSavePeriodOnUpdate()
        throws Exception
    {
        setUp();
        ((AbstractSessionDataStoreFactory)_factory).setSavePeriodSec(20);
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        long now = System.currentTimeMillis();

        //persist a session that is not expired, and has been saved before
        SessionData data = store.newSessionData("aaa19", 100, now - 10, now - 20, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setLastSaved(now - 100);
        persistSession(data);

        //update just the access and last access time
        data.setLastAccessed(now - 5);
        data.setAccessed(now - 1);

        //test that a save does not change the stored data
        store.store("aaa19", data);

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
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        long now = System.currentTimeMillis();
        //create a session that is not expired, and has never been saved before
        SessionData data = store.newSessionData("aaa20", 100, now - 10, now - 20, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());

        store.store("aaa20", data);

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
        setUp();
        _server.start();
        SessionDataStore store = _sessionManager.getSessionCache().getSessionDataStore();

        //persist a session that is not expired
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("aaa21", 100, now - 10, now - 20, TimeUnit.MINUTES.toMillis(60));
        data.setLastNode(_sessionIdManager.getWorkerName());
        data.setLastSaved(now - 100);
        data.setAttribute("wibble", "wobble");
        persistSession(data);

        //now change the attributes
        data.setAttribute("wibble", "bobble");
        data.setLastAccessed(now - 5);
        data.setAccessed(now - 1);

        store.store("aaa21", data);

        checkSessionPersisted(data);
    }
}
