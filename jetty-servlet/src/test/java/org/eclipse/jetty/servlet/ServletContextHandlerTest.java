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

package org.eclipse.jetty.servlet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ServletContextHandlerTest
{
    private Server _server;
    private LocalConnector _connector;

    private static final AtomicInteger __testServlets = new AtomicInteger();

    public static class MySCI implements ServletContainerInitializer
    {
        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
        {
            //add a programmatic listener
            if (ctx.getAttribute("MySCI.startup") != null)
                throw new IllegalStateException("MySCI already called");
            ctx.setAttribute("MySCI.startup", Boolean.TRUE);
            ctx.addListener(new MyContextListener());
        }
    }

    public static class MySCIStarter extends AbstractLifeCycle implements ServletContextHandler.ServletContainerInitializerCaller
    {
        MySCI _sci = new MySCI();
        ContextHandler.Context _ctx;

        MySCIStarter(ContextHandler.Context ctx)
        {
            _ctx = ctx;
        }

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            //call the SCI
            try
            {
                _ctx.setExtendedListenerTypes(true);
                _sci.onStartup(Collections.emptySet(), _ctx);
            }
            finally
            {

            }
        }
    }

    public static class MyContextListener implements ServletContextListener
    {

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            assertNull(sce.getServletContext().getAttribute("MyContextListener.contextInitialized"));
            sce.getServletContext().setAttribute("MyContextListener.contextInitialized", Boolean.TRUE);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            assertNull(sce.getServletContext().getAttribute("MyContextListener.contextDestroyed"));
            sce.getServletContext().setAttribute("MyContextListener.contextDestroyed", Boolean.TRUE);
        }
    }

    public static class MySessionHandler extends SessionHandler
    {
        public void checkSessionListeners(int size)
        {
            assertNotNull(_sessionListeners);
            assertEquals(size, _sessionListeners.size());
        }

        public void checkSessionAttributeListeners(int size)
        {
            assertNotNull(_sessionAttributeListeners);
            assertEquals(size, _sessionAttributeListeners.size());
        }

        public void checkSessionIdListeners(int size)
        {
            assertNotNull(_sessionIdListeners);
            assertEquals(size, _sessionIdListeners.size());
        }
    }

    public static class MyTestSessionListener implements HttpSessionAttributeListener, HttpSessionListener
    {        
        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
        }

        @Override
        public void attributeAdded(HttpSessionBindingEvent event)
        {
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event)
        {
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event)
        {
        }
    }
    
    public static class MySCAListener implements ServletContextAttributeListener
    {
        public static int adds = 0;
        public static int removes = 0;
        public static int replaces = 0;
        
        @Override
        public void attributeAdded(ServletContextAttributeEvent event)
        {
            ++adds;
        }

        @Override
        public void attributeRemoved(ServletContextAttributeEvent event)
        {
            ++removes;
        }

        @Override
        public void attributeReplaced(ServletContextAttributeEvent event)
        {
            ++replaces;
        }
    }

    public static class MyRequestListener implements ServletRequestListener
    {
        public static int destroys = 0;
        public static int inits = 0;

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        { 
            ++destroys;
        }

        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            ++inits;
        }
    }
    
    public static class MyRAListener implements ServletRequestAttributeListener
    {
        public static int adds = 0;
        public static int removes = 0;
        public static int replaces = 0;
        
        @Override
        public void attributeAdded(ServletRequestAttributeEvent srae)
        {
            ++adds;
        }

        @Override
        public void attributeRemoved(ServletRequestAttributeEvent srae)
        {
            ++removes;
        }

        @Override
        public void attributeReplaced(ServletRequestAttributeEvent srae)
        {
            ++replaces;
        }
    }
    
    public static class MySListener implements HttpSessionListener
    {
       public static int creates = 0;
       public static int destroys = 0;

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            ++creates;
        }

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            ++destroys;
        }
        
    }
    
    public static class MySAListener implements HttpSessionAttributeListener
    {
        public static int adds = 0;
        public static int removes = 0;
        public static int replaces = 0;
        
        @Override
        public void attributeAdded(HttpSessionBindingEvent event)
        {
            ++adds;
        }

        @Override
        public void attributeRemoved(HttpSessionBindingEvent event)
        {
            ++removes;
        }

        @Override
        public void attributeReplaced(HttpSessionBindingEvent event)
        {
            ++replaces;
        }   
    }
    
    
    public static class MySIListener implements HttpSessionIdListener
    {
        public static int changes = 0;
        
        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
            ++changes;
        }
    }
    
    public class InitialListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            //Add all of the sorts of listeners that are allowed
            try
            {
                MySCAListener mySCAListener = sce.getServletContext().createListener(MySCAListener.class);
                sce.getServletContext().addListener(mySCAListener);

                MyRequestListener myRequestListener = sce.getServletContext().createListener(MyRequestListener.class);
                sce.getServletContext().addListener(myRequestListener);

                MyRAListener myRAListener = sce.getServletContext().createListener(MyRAListener.class);
                sce.getServletContext().addListener(myRAListener);

                MySListener mySListener = sce.getServletContext().createListener(MySListener.class);
                sce.getServletContext().addListener(mySListener);

                MySAListener mySAListener = sce.getServletContext().createListener(MySAListener.class);
                sce.getServletContext().addListener(mySAListener);

                MySIListener mySIListener = sce.getServletContext().createListener(MySIListener.class);
                sce.getServletContext().addListener(mySIListener);
            }
            catch (Exception e)
            {
                fail(e);
            }
            //And also test you can't add a ServletContextListener from a ServletContextListener
            try
            {
                MyContextListener contextListener = sce.getServletContext().createListener(MyContextListener.class);
                sce.getServletContext().addListener(contextListener);
                fail("Adding SCI from an SCI!");
            }
            catch (IllegalArgumentException e)
            {
                //expected
            }
            catch (Exception x)
            {
                fail(x);
            }
            
            sce.getServletContext().setAttribute("foo", "bar");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            
        }
        
    }

    @BeforeEach
    public void createServer()
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        __testServlets.set(0);
    }

    @AfterEach
    public void destroyServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testAddSessionListener() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);

        MySessionHandler sessions = new MySessionHandler();
        root.setSessionHandler(sessions);
        assertNotNull(sessions);

        root.addEventListener(new MyTestSessionListener());
        sessions.checkSessionAttributeListeners(1);
        sessions.checkSessionIdListeners(0);
        sessions.checkSessionListeners(1);
    }

    @Test
    public void testListenerFromSCI() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/");
        root.addBean(new MySCIStarter(root.getServletContext()), true);
        _server.start();
        assertTrue((Boolean)root.getServletContext().getAttribute("MySCI.startup"));
        assertTrue((Boolean)root.getServletContext().getAttribute("MyContextListener.contextInitialized"));
    }
    
    @Test
    public void testListenersFromContextListener() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);
        ListenerHolder initialListener = new ListenerHolder();
        initialListener.setListener(new InitialListener());
        root.getServletHandler().addListener(initialListener);
        ServletHolder holder0 = root.addServlet(TestServlet.class, "/test");
        _server.start();
        
        ListenerHolder[] listenerHolders = root.getServletHandler().getListeners();
        assertNotNull(listenerHolders);
        for (ListenerHolder l : listenerHolders)
        {
            assertTrue(l.isStarted());
            assertNotNull(l.getListener());
            //all listeners except the first should be programmatic
            if (!"org.eclipse.jetty.servlet.ServletContextHandlerTest$InitialListener".equals(l.getClassName()))
            {
                assertFalse(root.isDurableListener(l.getListener()));
                assertTrue(root.isProgrammaticListener(l.getListener()));
            }
        }
        
        EventListener[] listeners = root.getEventListeners();
        assertNotNull(listeners);
        List<String> listenerClassNames = new ArrayList<>();
        for (EventListener l : listeners)
            listenerClassNames.add(l.getClass().getName());
 
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySCAListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MyRequestListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MyRAListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySAListener"));
        assertTrue(listenerClassNames.contains("org.eclipse.jetty.servlet.ServletContextHandlerTest$MySIListener"));
        
        //test ServletRequestAttributeListener
        String response = _connector.getResponse("GET /test?req=all HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MyRAListener.adds);
        assertEquals(1, MyRAListener.replaces);
        assertEquals(1, MyRAListener.removes);
        
        //test HttpSessionAttributeListener
        response = _connector.getResponse("GET /test?session=create HTTP/1.0\r\n\r\n");
        String sessionid = response.substring(response.indexOf("JSESSIONID"), response.indexOf(";"));
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.creates);
        assertEquals(1, MySAListener.adds);
        assertEquals(0, MySAListener.replaces);
        assertEquals(0, MySAListener.removes);
        StringBuffer request = new StringBuffer();
        request.append("GET /test?session=replace HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: "+sessionid+"\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.creates);
        assertEquals(1, MySAListener.adds);
        assertEquals(1, MySAListener.replaces);
        assertEquals(0, MySAListener.removes);
        request = new StringBuffer();
        request.append("GET /test?session=remove HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: "+sessionid+"\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.creates);
        assertEquals(1, MySAListener.adds);
        assertEquals(1, MySAListener.replaces);
        assertEquals(1, MySAListener.removes);
        
        //test HttpSessionIdListener.sessionIdChanged
        request = new StringBuffer();
        request.append("GET /test?session=change HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: "+sessionid+"\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySIListener.changes);
        sessionid = response.substring(response.indexOf("JSESSIONID"), response.indexOf(";"));
        
        //test HttpServletListener.sessionDestroyed
        request = new StringBuffer();
        request.append("GET /test?session=delete HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("Cookie: "+sessionid+"\n");
        request.append("\n");
        response = _connector.getResponse(request.toString());
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySListener.destroys);
        
        //test ServletContextAttributeListener
        //attribute was set when context listener registered
        assertEquals(1, MySCAListener.adds);
        response = _connector.getResponse("GET /test?ctx=all HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));
        assertEquals(1, MySCAListener.replaces);
        assertEquals(1, MySCAListener.removes);
    }

    @Test
    public void testFindContainer() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts, "/", ServletContextHandler.SESSIONS);

        SessionHandler session = root.getSessionHandler();
        ServletHandler servlet = root.getServletHandler();
        SecurityHandler security = new ConstraintSecurityHandler();
        root.setSecurityHandler(security);

        _server.start();

        assertEquals(root, AbstractHandlerContainer.findContainerOf(_server, ContextHandler.class, session));
        assertEquals(root, AbstractHandlerContainer.findContainerOf(_server, ContextHandler.class, security));
        assertEquals(root, AbstractHandlerContainer.findContainerOf(_server, ContextHandler.class, servlet));
    }

    @Test
    public void testInitOrder() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        ServletHolder holder0 = context.addServlet(TestServlet.class, "/test0");
        ServletHolder holder1 = context.addServlet(TestServlet.class, "/test1");
        ServletHolder holder2 = context.addServlet(TestServlet.class, "/test2");

        holder1.setInitOrder(1);
        holder2.setInitOrder(2);

        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        assertEquals(2, __testServlets.get());

        String response = _connector.getResponse("GET /test1 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));

        assertEquals(2, __testServlets.get());

        response = _connector.getResponse("GET /test2 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("200 OK"));

        assertEquals(2, __testServlets.get());

        assertThat(holder0.getServletInstance(), nullValue());
        response = _connector.getResponse("GET /test0 HTTP/1.0\r\n\r\n");
        assertThat(response, containsString("200 OK"));
        assertEquals(3, __testServlets.get());
        assertThat(holder0.getServletInstance(), notNullValue(Servlet.class));

        _server.stop();
        assertEquals(0, __testServlets.get());

        holder0.setInitOrder(0);
        _server.start();
        assertEquals(3, __testServlets.get());
        assertThat(holder0.getServletInstance(), notNullValue(Servlet.class));
        _server.stop();
        assertEquals(0, __testServlets.get());
    }

    @Test
    public void testAddServletAfterStart() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        int result;
        assertThat("Response", response, containsString("Test"));

        context.addServlet(HelloServlet.class, "/hello");

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testHandlerBeforeServletHandler() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

        HandlerWrapper extra = new HandlerWrapper();

        context.getSessionHandler().insertHandler(extra);

        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        int result;
        assertThat("Response", response, containsString("Test"));

        assertEquals(extra, context.getSessionHandler().getHandler());
    }

    @Test
    public void testGzipHandlerOption() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS | ServletContextHandler.GZIP);
        GzipHandler gzip = context.getGzipHandler();
        _server.start();
        assertEquals(context.getSessionHandler(), context.getHandler());
        assertEquals(gzip, context.getSessionHandler().getHandler());
        assertEquals(context.getServletHandler(), gzip.getHandler());
    }

    @Test
    public void testGzipHandlerSet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.setSessionHandler(new SessionHandler());
        context.setGzipHandler(new GzipHandler());
        GzipHandler gzip = context.getGzipHandler();
        _server.start();
        assertEquals(context.getSessionHandler(), context.getHandler());
        assertEquals(gzip, context.getSessionHandler().getHandler());
        assertEquals(context.getServletHandler(), gzip.getHandler());
    }

    @Test
    public void testReplaceServletHandlerWithServlet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        int result;
        assertThat("Response", response, containsString("Test"));

        context.stop();
        ServletHandler srvHnd = new ServletHandler();
        srvHnd.addServletWithMapping(HelloServlet.class, "/hello");
        context.setServletHandler(srvHnd);
        context.start();

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testReplaceServletHandlerWithoutServlet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class, "/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponse(request.toString());
        int result;
        assertThat("Response", response, containsString("Test"));

        context.stop();
        ServletHandler srvHnd = new ServletHandler();
        context.setServletHandler(srvHnd);
        context.start();

        context.addServlet(HelloServlet.class, "/hello");

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponse(request.toString());
        assertThat("Response", response, containsString("Hello World"));
    }

    @Test
    public void testReplaceHandler() throws Exception
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        ServletHolder sh = new ServletHolder(new TestServlet());
        servletContextHandler.addServlet(sh, "/foo");
        final AtomicBoolean contextInit = new AtomicBoolean(false);
        final AtomicBoolean contextDestroy = new AtomicBoolean(false);

        servletContextHandler.addEventListener(new ServletContextListener()
        {

            @Override
            public void contextInitialized(ServletContextEvent sce)
            {
                if (sce.getServletContext() != null)
                    contextInit.set(true);
            }

            @Override
            public void contextDestroyed(ServletContextEvent sce)
            {
                if (sce.getServletContext() != null)
                    contextDestroy.set(true);
            }
        });
        ServletHandler shandler = servletContextHandler.getServletHandler();

        ResourceHandler rh = new ResourceHandler();

        servletContextHandler.insertHandler(rh);
        assertEquals(shandler, servletContextHandler.getServletHandler());
        assertEquals(rh, servletContextHandler.getHandler());
        assertEquals(rh.getHandler(), shandler);
        _server.setHandler(servletContextHandler);
        _server.start();
        assertTrue(contextInit.get());
        _server.stop();
        assertTrue(contextDestroy.get());
    }

    @Test
    public void testFallThrough() throws Exception
    {
        HandlerList list = new HandlerList();
        _server.setHandler(list);

        ServletContextHandler root = new ServletContextHandler(list, "/", ServletContextHandler.SESSIONS);

        ServletHandler servlet = root.getServletHandler();
        servlet.setEnsureDefaultServlet(false);
        servlet.addServletWithMapping(HelloServlet.class, "/hello/*");

        list.addHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.sendError(404, "Fell Through");
            }
        });

        _server.start();

        String response = _connector.getResponse("GET /hello HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("200 OK"));

        response = _connector.getResponse("GET /other HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("404 Fell Through"));
    }

    /**
     * Test behavior of new {@link org.eclipse.jetty.util.Decorator}, with
     * new DecoratedObjectFactory class
     *
     * @throws Exception on test failure
     */
    @Test
    public void testUtilDecorator() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.getObjectFactory().addDecorator(new DummyUtilDecorator());
        _server.setHandler(context);

        context.addServlet(DecoratedObjectFactoryServlet.class, "/objfactory/*");
        _server.start();

        String response = _connector.getResponse("GET /objfactory/ HTTP/1.0\r\n\r\n");
        assertThat("Response status code", response, containsString("200 OK"));

        String expected = String.format("Attribute[%s] = %s", DecoratedObjectFactory.ATTR, DecoratedObjectFactory.class.getName());
        assertThat("Has context attribute", response, containsString(expected));

        assertThat("Decorators size", response, containsString("Decorators.size = [2]"));

        expected = String.format("decorator[] = %s", DummyUtilDecorator.class.getName());
        assertThat("Specific Legacy Decorator", response, containsString(expected));
    }

    public static class HelloServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter writer = resp.getWriter();
            writer.write("Hello World");
        }
    }

    public static class DummyUtilDecorator implements org.eclipse.jetty.util.Decorator
    {
        @Override
        public <T> T decorate(T o)
        {
            return o;
        }

        @Override
        public void destroy(Object o)
        {
        }
    }

    public static class DecoratedObjectFactoryServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter out = resp.getWriter();

            Object obj = req.getServletContext().getAttribute(DecoratedObjectFactory.ATTR);
            out.printf("Attribute[%s] = %s%n", DecoratedObjectFactory.ATTR, obj.getClass().getName());

            if (obj instanceof DecoratedObjectFactory)
            {
                out.printf("Object is a DecoratedObjectFactory%n");
                DecoratedObjectFactory objFactory = (DecoratedObjectFactory)obj;
                List<Decorator> decorators = objFactory.getDecorators();
                out.printf("Decorators.size = [%d]%n", decorators.size());
                for (Decorator decorator : decorators)
                {
                    out.printf(" decorator[] = %s%n", decorator.getClass().getName());
                }
            }
            else
            {
                out.printf("Object is NOT a DecoratedObjectFactory%n");
            }
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        public void destroy()
        {
            super.destroy();
            __testServlets.decrementAndGet();
        }

        @Override
        public void init() throws ServletException
        {
            __testServlets.incrementAndGet();
            super.init();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter writer = resp.getWriter();
            writer.write("Test");
            
            String action = req.getParameter("session");
            if (!Objects.isNull(action))
            {
                if ("create".equalsIgnoreCase(action))
                {
                    //Make a session
                    HttpSession session = req.getSession(true);
                    session.setAttribute("some", "thing");
                }
                else if ("change".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(true);
                    req.changeSessionId();
                }
                else if ("replace".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(false);
                    session.setAttribute("some", "other");
                }
                else if ("remove".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(false);
                    session.removeAttribute("some");
                }
                else if ("delete".equalsIgnoreCase(action))
                {
                    HttpSession session = req.getSession(false);
                    session.invalidate();
                }
                else
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                
                return;
            }

            action = req.getParameter("req");
            if (!Objects.isNull(action))
            {
                //test all attribute ops
                req.setAttribute("some", "value");
                req.setAttribute("some", "other");
                req.removeAttribute("some");
                
                return;
            }
           
            action = req.getParameter("ctx");
            if (!Objects.isNull(action))
            {
                //change and remove context attribute
                req.getServletContext().setAttribute("foo", "foo");
                req.getServletContext().removeAttribute("foo");
                
                return;
            }
        }
    }
}
