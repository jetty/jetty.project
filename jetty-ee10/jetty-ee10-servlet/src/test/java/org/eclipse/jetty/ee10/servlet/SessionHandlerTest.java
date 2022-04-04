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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
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
            Session session = sessionHandler.newSession(null, "1234");
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
            Session session = sessionHandler.newSession(null, "1234");
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
}
