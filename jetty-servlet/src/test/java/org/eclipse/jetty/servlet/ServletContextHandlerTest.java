//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.Servlet;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ServletContextHandlerTest
{
    private Server _server;
    private LocalConnector _connector;

    private static final AtomicInteger __testServlets = new AtomicInteger();
    
    @Before
    public void createServer()
    {
        _server = new Server();

        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        __testServlets.set(0);
    }

    @After
    public void destroyServer() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testFindContainer() throws Exception
    {
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        _server.setHandler(contexts);

        ServletContextHandler root = new ServletContextHandler(contexts,"/",ServletContextHandler.SESSIONS);

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
        ServletHolder holder0 = context.addServlet(TestServlet.class,"/test0");
        ServletHolder holder1 = context.addServlet(TestServlet.class,"/test1");
        ServletHolder holder2 = context.addServlet(TestServlet.class,"/test2");
        
        holder1.setInitOrder(1);
        holder2.setInitOrder(2);
        
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();
        
        assertEquals(2,__testServlets.get());
        
        String response =_connector.getResponses("GET /test1 HTTP/1.0\r\n\r\n");
        Assert.assertThat(response,Matchers.containsString("200 OK"));
        
        assertEquals(2,__testServlets.get());
        
        response =_connector.getResponses("GET /test2 HTTP/1.0\r\n\r\n");
        Assert.assertThat(response,containsString("200 OK"));
        
        assertEquals(2,__testServlets.get());
        
        assertThat(holder0.getServletInstance(),nullValue());
        response =_connector.getResponses("GET /test0 HTTP/1.0\r\n\r\n");
        assertThat(response,containsString("200 OK"));
        assertEquals(3,__testServlets.get());
        assertThat(holder0.getServletInstance(),notNullValue(Servlet.class));

        _server.stop();
        assertEquals(0,__testServlets.get());
        
        holder0.setInitOrder(0);
        _server.start();
        assertEquals(3,__testServlets.get());
        assertThat(holder0.getServletInstance(),notNullValue(Servlet.class));
        _server.stop();
        assertEquals(0,__testServlets.get());
        
    }

    @Test
    public void testAddServletAfterStart() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class,"/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponses(request.toString());
        assertResponseContains("Test", response);

        context.addServlet(HelloServlet.class, "/hello");

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponses(request.toString());
        assertResponseContains("Hello World", response);
    }

    @Test
    public void testReplaceServletHandlerWithServlet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class,"/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponses(request.toString());
        assertResponseContains("Test", response);

        context.stop();
        ServletHandler srvHnd = new ServletHandler();
        srvHnd.addServletWithMapping(HelloServlet.class,"/hello");
        context.setServletHandler(srvHnd);
        context.start();

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponses(request.toString());
        assertResponseContains("Hello World", response);
    }

    @Test
    public void testReplaceServletHandlerWithoutServlet() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler();
        context.addServlet(TestServlet.class,"/test");
        context.setContextPath("/");
        _server.setHandler(context);
        _server.start();

        StringBuffer request = new StringBuffer();
        request.append("GET /test HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        String response = _connector.getResponses(request.toString());
        assertResponseContains("Test", response);

        context.stop();
        ServletHandler srvHnd = new ServletHandler();
        context.setServletHandler(srvHnd);
        context.start();

        context.addServlet(HelloServlet.class,"/hello");

        request = new StringBuffer();
        request.append("GET /hello HTTP/1.0\n");
        request.append("Host: localhost\n");
        request.append("\n");

        response = _connector.getResponses(request.toString());
        assertResponseContains("Hello World", response);
    }
    
    @Test
    public void testReplaceHandler () throws Exception
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        ServletHolder sh =  new ServletHolder(new TestServlet());
        servletContextHandler.addServlet(sh, "/foo");
        final AtomicBoolean contextInit = new AtomicBoolean(false);
        final AtomicBoolean contextDestroy = new AtomicBoolean(false);
        
        servletContextHandler.addEventListener(new ServletContextListener() {

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

        servletContextHandler.setHandler(rh);    
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

        ServletContextHandler root = new ServletContextHandler(list,"/",ServletContextHandler.SESSIONS);

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

        String response= _connector.getResponses("GET /hello HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("200 OK"));
        
        response= _connector.getResponses("GET /other HTTP/1.0\r\n\r\n");
        Assert.assertThat(response, Matchers.containsString("404 Fell Through"));
        
    }

    private int assertResponseContains(String expected, String response)
    {
        int idx = response.indexOf(expected);
        if (idx == (-1))
        {
            // Not found
            StringBuffer err = new StringBuffer();
            err.append("Response does not contain expected string \"").append(expected).append("\"");
            err.append("\n").append(response);

            System.err.println(err);
            Assert.fail(err.toString());
        }
        return idx;
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
        }
    }
}
