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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Decorator;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class ServletLifeCycleTest
{
    static final Queue<String> events = new ConcurrentLinkedQueue<>();

    @Test
    public void testLifeCycle() throws Exception
    {
        Server server = new Server(0);
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        context.getObjectFactory().addDecorator(new TestDecorator());

        // TODO review this test in jetty-10.  Instances that are created externally and passed in should not be
        // TODO decorated by the object factory unless: a) there is an explicit call to ServletContext.createXxx;
        // TODO ; and b) the Servlet dyanmic API is used to register them.

        ServletHandler sh = context.getServletHandler();
        sh.addListener(new ListenerHolder(TestListener.class)); //added directly to ServletHandler
        context.addEventListener(context.getServletContext().createListener(TestListener2.class)); //create,decorate and add listener to context - no holder!

        sh.addFilterWithMapping(TestFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        sh.addFilterWithMapping(new FilterHolder(context.getServletContext().createFilter(TestFilter2.class)), "/*", EnumSet.of(DispatcherType.REQUEST));

        sh.addServletWithMapping(TestServlet.class, "/1/*").setInitOrder(1);
        sh.addServletWithMapping(TestServlet2.class, "/2/*").setInitOrder(-1);
        sh.addServletWithMapping(new ServletHolder(context.getServletContext().createServlet(TestServlet3.class))
        {
            {
                setInitOrder(1);
            }
        }, "/3/*");

        assertThat(events, Matchers.contains(
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener2",
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter2",
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet3"));

        events.clear();
        server.start();
        assertThat(events, Matchers.contains(
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener",
            "ContextInitialized class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener2",
            "ContextInitialized class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener",
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter",
            "init class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter",
            "init class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter2",
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet",
            "init class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet",
            "init class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet3"));

        events.clear();
        connector.getResponse("GET /2/info HTTP/1.0\r\n\r\n");

        assertThat(events, Matchers.contains(
            "Decorate class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet2",
            "init class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet2",
            "doFilter class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter",
            "doFilter class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter2",
            "service class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet2"));

        events.clear();
        server.stop();

        assertThat(events, Matchers.contains(
            "Destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter2",
            "destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter2",
            "Destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter",
            "destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestFilter",
            "Destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet3",
            "destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet3",
            "Destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet2",
            "destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet2",
            "Destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet",
            "destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestServlet",
            "contextDestroyed class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener",
            "contextDestroyed class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener2",
            "Destroy class org.eclipse.jetty.ee9.servlet.ServletLifeCycleTest$TestListener"
        ));

        // Listener added before start is not destroyed
        List<EventListener> listeners = context.getEventListeners();
        assertThat(listeners.size(), is(1));
        assertThat(listeners.get(0).getClass(), is(TestListener2.class));

        server.start();
        context.addEventListener(new EventListener() {});
        listeners = context.getEventListeners();
        listeners.forEach(System.err::println);
        assertThat(listeners.size(), greaterThanOrEqualTo(3));

        server.stop();
        listeners = context.getEventListeners();
        assertThat(listeners.size(), is(1));
        assertThat(listeners.get(0).getClass(), is(TestListener2.class));
    }

    public static class TestDecorator implements Decorator
    {
        @Override
        public <T> T decorate(T o)
        {
            events.add("Decorate " + o.getClass());
            return o;
        }

        @Override
        public void destroy(Object o)
        {
            events.add("Destroy " + o.getClass());
        }
    }

    public static class TestListener implements ServletContextListener
    {
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            events.add("ContextInitialized " + this.getClass());
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            events.add("contextDestroyed " + this.getClass());
        }
    }

    public static class TestListener2 extends TestListener
    {
    }

    public static class TestFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            events.add("init " + this.getClass());
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            events.add("doFilter " + this.getClass());
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
            events.add("destroy " + this.getClass());
        }
    }

    public static class TestFilter2 extends TestFilter
    {
    }

    public static class TestServlet implements Servlet
    {
        @Override
        public void init(ServletConfig config) throws ServletException
        {
            events.add("init " + this.getClass());
        }

        @Override
        public ServletConfig getServletConfig()
        {
            return null;
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            events.add("service " + this.getClass());
        }

        @Override
        public String getServletInfo()
        {
            return null;
        }

        @Override
        public void destroy()
        {
            events.add("destroy " + this.getClass());
        }
    }

    public static class TestServlet2 extends TestServlet
    {
    }

    public static class TestServlet3 extends TestServlet
    {
    }
}
