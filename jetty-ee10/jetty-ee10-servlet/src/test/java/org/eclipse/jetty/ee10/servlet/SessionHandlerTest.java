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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.servlet.ServletException;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.session.AbstractSessionCache;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.HouseKeeper;
import org.eclipse.jetty.session.NullSessionDataStore;
import org.eclipse.jetty.session.NullSessionDataStoreFactory;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionCache;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class SessionHandlerTest
{
    public WorkDir workDir;
    
    public static class SessionConsumer implements Consumer<Session>
    {
        private Session _session;
        
        @Override
        public void accept(Session s)
        {
            _session = s;
        }
        
        public Session getSession()
        {
            return _session;
        }
    }
    
    @Test
    public void testSessionTrackingMode()
    {
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionTrackingModes(new HashSet<>(Arrays.asList(SessionTrackingMode.COOKIE, SessionTrackingMode.URL)));
        sessionHandler.setSessionTrackingModes(Collections.singleton(SessionTrackingMode.SSL));
        assertThrows(IllegalArgumentException.class, () -> sessionHandler.setSessionTrackingModes(new HashSet<>(Arrays.asList(SessionTrackingMode.SSL, SessionTrackingMode.URL))));
    }
    
    /**
     * Test that a session listener can access classes only visible to the context it is in.
     */
    @Test
    public void testSessionListenerWithClassloader() throws Exception
    {
        Path foodir = workDir.getEmptyPathDir();
        Path fooClass = foodir.resolve("Foo.class");
       
        //Use a class that would only be known to the webapp classloader
        try (InputStream foostream = Thread.currentThread().getContextClassLoader().getResourceAsStream("Foo.clazz");
             OutputStream out = Files.newOutputStream(fooClass))
        {
            IO.copy(foostream, out);
        }
       
        assertTrue(Files.exists(fooClass));
        assertThat(Files.size(fooClass), greaterThan(0L));
       
        URL[] foodirUrls = new URL[]{foodir.toUri().toURL()};
        URLClassLoader contextClassLoader = new URLClassLoader(foodirUrls, Thread.currentThread().getContextClassLoader());
        
        Server server = new Server();
        ServletContextHandler sch = new ServletContextHandler();
        sch.setContextPath("/");
        sch.setClassLoader(contextClassLoader);
        server.setHandler(sch);
        SessionHandler sessionHandler = new SessionHandler();
        sch.setSessionHandler(sessionHandler);

        class ListenerWithClasses implements HttpSessionListener
        {
            Exception _e;
            boolean _called;

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                //try loading a class that is known only to the webapp
                //to test that the calling thread has been properly
                //annointed with the webapp's classloader
                try
                {
                    _called = true;
                    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass("Foo");
                }
                catch (Exception cnfe)
                {
                    _e = cnfe;
                }
            }
        }

        try
        {
            ListenerWithClasses listener = new ListenerWithClasses();
            sessionHandler.addEventListener(listener);
            sessionHandler.setServer(server);
            server.start();
            //create the session
            SessionConsumer consumer = new SessionConsumer();
            sessionHandler.newSession(null, "1234", consumer);
            Session session = consumer.getSession();
            String id = session.getId();
            assertNotNull(session);

            //invalidate the session and check that context classes could be accessed
            session.invalidate();
            assertFalse(sch.getSessionHandler().getSessionCache().contains(id));
            assertFalse(sch.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
            assertTrue(listener._called);
            assertNull(listener._e);
        }
        finally
        {
            server.stop();
        }
    }
    
    /**
     * Test that if a session listener throws an exception during sessionDestroyed the session is still invalidated
     */
    @Test
    public void testSessionListenerWithException() throws Exception
    {
        Server server = new Server();
        ServletContextHandler sch = new ServletContextHandler();
        server.setHandler(sch);
        SessionHandler sessionHandler = new SessionHandler();
        sch.setSessionHandler(sessionHandler);
        
        class Listener1 implements HttpSessionListener
        {
            boolean _destroyCalled = false;
            boolean _createCalled = false;
            
            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                _createCalled = true;
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                _destroyCalled = true;
                throw new IllegalStateException("Exception during sessionDestroyed");
            }
        }
        
        try (StacklessLogging ignore = new StacklessLogging(ServletHandler.class, Session.class))
        {
            Listener1 listener = new Listener1();
            sessionHandler.addEventListener(listener);
            sessionHandler.setServer(server);
            server.start();
            SessionConsumer consumer = new SessionConsumer();
            sessionHandler.newSession(null, "1234", consumer);
            Session session = consumer.getSession();
            String id = session.getId();
            assertNotNull(session);
            assertTrue(listener._createCalled);

            session.invalidate();
            //check session no longer exists
            assertFalse(sch.getSessionHandler().getSessionCache().contains(id));
            assertFalse(sch.getSessionHandler().getSessionCache().getSessionDataStore().exists(id));
            assertTrue(listener._destroyCalled);
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSessionListenerOrdering()
        throws Exception
    {
        final StringBuffer result = new StringBuffer();

        class Listener1 implements HttpSessionListener
        {

            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                result.append("Listener1 create;");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                result.append("Listener1 destroy;");
            }
        }

        class Listener2 implements HttpSessionListener
        {

            @Override
            public void sessionCreated(HttpSessionEvent se)
            {
                result.append("Listener2 create;");
            }

            @Override
            public void sessionDestroyed(HttpSessionEvent se)
            {
                result.append("Listener2 destroy;");
            }

        }

        Server server = new Server();
        ServletContextHandler sch = new ServletContextHandler();
        server.setHandler(sch);
        SessionHandler sessionHandler = new SessionHandler();
        sch.setSessionHandler(sessionHandler);
        try
        {
            sessionHandler.addEventListener(new Listener1());
            sessionHandler.addEventListener(new Listener2());
            sessionHandler.setServer(server);
            server.start();
            Session session = new Session(sessionHandler, new SessionData("aa", "_", "0.0", 0, 0, 0, 0));
            sessionHandler.callSessionCreatedListeners(session);
            sessionHandler.callSessionDestroyedListeners(session);
            assertEquals("Listener1 create;Listener2 create;Listener2 destroy;Listener1 destroy;", result.toString());
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testSimpleSessionCreation() throws Exception
    {
        String contextPath = "";
        String servletMapping = "/server";

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.addBean(sessionIdManager, true);
        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setEvictionPolicy(SessionCache.NEVER_EVICT);
        server.addBean(cacheFactory);

        SessionDataStoreFactory storeFactory = new NullSessionDataStoreFactory();
        server.addBean(storeFactory);

        HouseKeeper housekeeper = new HouseKeeper();
        housekeeper.setIntervalSec(-1); //turn off scavenging
        sessionIdManager.setSessionHouseKeeper(housekeeper);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath(contextPath);
        server.setHandler(context);
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setSessionIdManager(sessionIdManager);
        sessionHandler.setMaxInactiveInterval(-1); //immortal session
        context.setSessionHandler(sessionHandler);

        TestServlet servlet = new TestServlet();
        ServletHolder holder = new ServletHolder(servlet);
        context.addServlet(holder, servletMapping);

        server.start();
        int port = connector.getLocalPort();
        try (StacklessLogging stackless = new StacklessLogging(SessionHandlerTest.class.getPackage()))
        {
            HttpClient client = new HttpClient();
            client.start();

            //make a session
            String url = "http://localhost:" + port + contextPath + servletMapping + "?action=create";

            //make a request to set up a session on the server
            ContentResponse response = client.GET(url);
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());

            String sessionCookie = response.getHeaders().get("Set-Cookie");
            assertTrue(sessionCookie != null);

            ContentResponse response2 = client.GET("http://localhost:" + port + contextPath + servletMapping + "?action=test");
            assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
        }
        finally
        {
            server.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;
        public String _id = null;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if (action != null && action.startsWith("create"))
            {
                HttpSession session = request.getSession(true);
                assertNotNull(session);
                _id = session.getId();
                session.setAttribute("value", 1);
                return;
            }
            else if (action != null && "test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertNotNull(session);
                assertEquals(_id, session.getId());
                return;
            }
        }
    }

    public class MockSessionCache extends AbstractSessionCache
    {

        public MockSessionCache(SessionHandler manager)
        {
            super(manager);
        }

        @Override
        public void shutdown()
        {
        }

        @Override
        public Session doGet(String key)
        {
            return null;
        }

        @Override
        public Session doPutIfAbsent(String key, Session session)
        {
            return null;
        }

        @Override
        public Session doDelete(String key)
        {
            return null;
        }

        @Override
        public boolean doReplace(String id, Session oldValue, Session newValue)
        {
            return false;
        }

        @Override
        public Session newSession(SessionData data)
        {
            return null;
        }

        @Override
        protected Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction)
        {
            return mappingFunction.apply(id);
        }
    }

    public class MockSessionIdManager extends DefaultSessionIdManager
    {
        public MockSessionIdManager(Server server)
        {
            super(server);
        }

        @Override
        public boolean isIdInUse(String id)
        {
            return false;
        }

        @Override
        public void expireAll(String id)
        {

        }

        @Override
        public String renewSessionId(String oldClusterId, String oldNodeId, Request request)
        {
            return "";
        }
    }
    
    @Test
    public void testSessionCookie() throws Exception
    {
        Server server = new Server();
        MockSessionIdManager idMgr = new MockSessionIdManager(server);
        idMgr.setWorkerName("node1");
        SessionHandler mgr = new SessionHandler();
        MockSessionCache cache = new MockSessionCache(mgr);
        cache.setSessionDataStore(new NullSessionDataStore());
        mgr.setSessionCache(cache);
        mgr.setSessionIdManager(idMgr);

        long now = System.currentTimeMillis();

        Session session = new Session(mgr, new SessionData("123", "_foo", "0.0.0.0", now, now, now, 30));
        session.setExtendedId("123.node1");
        SessionCookieConfig sessionCookieConfig = mgr.getSessionCookieConfig();
        sessionCookieConfig.setName("SPECIAL");
        sessionCookieConfig.setDomain("universe");
        sessionCookieConfig.setHttpOnly(false);
        sessionCookieConfig.setSecure(false);
        sessionCookieConfig.setPath("/foo");
        sessionCookieConfig.setMaxAge(99);
        sessionCookieConfig.setAttribute("SameSite", "Strict");
        sessionCookieConfig.setAttribute("ham", "cheese");
        
        HttpCookie cookie = mgr.getSessionCookie(session, "/bar", false);
        assertEquals("SPECIAL", cookie.getName());
        assertEquals("universe", cookie.getDomain());
        assertEquals("/foo", cookie.getPath());
        assertFalse(cookie.isHttpOnly());
        assertFalse(cookie.isSecure());
        assertEquals(99, cookie.getMaxAge());
        assertEquals(HttpCookie.SameSite.STRICT, cookie.getSameSite());
        
        String cookieStr = cookie.getRFC6265SetCookie();
        assertThat(cookieStr, containsString("; SameSite=Strict; ham=cheese"));
    }

    @Test
    public void testSecureSessionCookie() throws Exception
    {
        Server server = new Server();
        MockSessionIdManager idMgr = new MockSessionIdManager(server);
        idMgr.setWorkerName("node1");
        SessionHandler mgr = new SessionHandler();
        MockSessionCache cache = new MockSessionCache(mgr);
        cache.setSessionDataStore(new NullSessionDataStore());
        mgr.setSessionCache(cache);
        mgr.setSessionIdManager(idMgr);

        long now = System.currentTimeMillis();

        Session session = new Session(mgr, new SessionData("123", "_foo", "0.0.0.0", now, now, now, 30));

        SessionCookieConfig sessionCookieConfig = mgr.getSessionCookieConfig();
        sessionCookieConfig.setSecure(true);

        //sessionCookieConfig.secure == true, always mark cookie as secure, irrespective of if requestIsSecure
        HttpCookie cookie = mgr.getSessionCookie(session, "/foo", true);
        assertTrue(cookie.isSecure());
        //sessionCookieConfig.secure == true, always mark cookie as secure, irrespective of if requestIsSecure
        cookie = mgr.getSessionCookie(session, "/foo", false);
        assertTrue(cookie.isSecure());

        //sessionCookieConfig.secure==false, setSecureRequestOnly==true, requestIsSecure==true
        //cookie should be secure: see SessionCookieConfig.setSecure() javadoc
        sessionCookieConfig.setSecure(false);
        cookie = mgr.getSessionCookie(session, "/foo", true);
        assertTrue(cookie.isSecure());

        //sessionCookieConfig.secure=false, setSecureRequestOnly==true, requestIsSecure==false
        //cookie is not secure: see SessionCookieConfig.setSecure() javadoc
        cookie = mgr.getSessionCookie(session, "/foo", false);
        assertFalse(cookie.isSecure());

        //sessionCookieConfig.secure=false, setSecureRequestOnly==false, requestIsSecure==false
        //cookie is not secure: not a secure request
        mgr.setSecureRequestOnly(false);
        cookie = mgr.getSessionCookie(session, "/foo", false);
        assertFalse(cookie.isSecure());

        //sessionCookieConfig.secure=false, setSecureRequestOnly==false, requestIsSecure==true
        //cookie is not secure: not on secured requests and request is secure
        cookie = mgr.getSessionCookie(session, "/foo", true);
        assertFalse(cookie.isSecure());
    }
}
