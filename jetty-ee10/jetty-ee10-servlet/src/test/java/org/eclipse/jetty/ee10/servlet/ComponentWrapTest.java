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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ComponentWrapTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ComponentWrapTest.class);
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testSimpleFilterServletAndListener() throws Exception
    {
        EventQueue events = new EventQueue();
        WrapHandler wrapHandler = new WrapHandler(events);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        ServletHolder servletHolder = new ServletHolder(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.setContentType("text/plain");
                resp.setCharacterEncoding("utf-8");
                resp.getWriter().println("hello");
            }
        });
        contextHandler.addServlet(servletHolder, "/hello");
        FilterHolder filterHolder = new FilterHolder(new Filter()
        {
            @Override
            public void init(FilterConfig filterConfig)
            {
                events.addEvent("TestFilter.init");
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                events.addEvent("TestFilter.doFilter");
                chain.doFilter(request, response);
                events.addEvent("TestFilter.filtered");
            }

            @Override
            public void destroy()
            {
            }
        });
        contextHandler.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));

        ListenerHolder listenerHolder = new ListenerHolder(LoggingRequestListener.class);
        contextHandler.getServletHandler().addListener(listenerHolder);

        contextHandler.addBean(wrapHandler);
        server.setHandler(contextHandler);
        server.start();

        ContentResponse response = client.GET(server.getURI().resolve("/hello"));
        assertThat("Response.status", response.getStatus(), is(HttpStatus.OK_200));

        List<String> expectedEvents = new ArrayList<>();
        expectedEvents.add("TestWrapFilter.init()");
        expectedEvents.add("TestFilter.init");
        expectedEvents.add("TestWrapServlet.init()");
        expectedEvents.add("TestWrapListener.requestInitialized()");
        expectedEvents.add("TestWrapFilter.doFilter()");
        expectedEvents.add("TestFilter.doFilter");
        expectedEvents.add("TestWrapServlet.service()");
        expectedEvents.add("TestFilter.filtered");
        expectedEvents.add("TestWrapListener.requestDestroyed()");

        Iterator<String> i = events.iterator();
        for (String s: expectedEvents)
        {
            assertEquals(s, i.next());
        }
        assertFalse(i.hasNext());
    }

    public static class LoggingRequestListener implements ServletRequestListener
    {
        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            LOG.info("requestDestroyed()");
        }

        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            LOG.info("requestInitialized()");
        }
    }

    public static class EventQueue extends LinkedBlockingQueue<String>
    {
        private static final Logger LOG = LoggerFactory.getLogger(EventQueue.class);

        public void addEvent(String format, Object... args)
        {
            String eventText = String.format(format, args);
            offer(eventText);
            Throwable cause = null;
            if (args.length > 0)
            {
                Object lastArg = args[args.length - 1];
                if (lastArg instanceof Throwable)
                {
                    cause = (Throwable)lastArg;
                }
            }
            LOG.info("[EVENT] {}", eventText, cause);
        }
    }

    public static class WrapHandler implements
        FilterHolder.WrapFunction,
        ServletHolder.WrapFunction,
        ListenerHolder.WrapFunction
    {
        private EventQueue events;

        public WrapHandler(EventQueue events)
        {
            this.events = events;
        }

        @Override
        public Filter wrapFilter(Filter filter)
        {
            return new TestWrapFilter(filter, events);
        }

        @Override
        public EventListener wrapEventListener(EventListener listener)
        {
            if (listener instanceof ServletRequestListener)
            {
                return new TestWrapListener((ServletRequestListener)listener, events);
            }
            return listener;
        }

        @Override
        public Servlet wrapServlet(Servlet servlet)
        {
            return new TestWrapServlet(servlet, events);
        }
    }

    public static class TestWrapFilter extends FilterHolder.Wrapper
    {
        private EventQueue events;

        public TestWrapFilter(Filter filter, EventQueue events)
        {
            super(filter);
            this.events = events;
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            events.addEvent("TestWrapFilter.init()");
            super.init(filterConfig);
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            events.addEvent("TestWrapFilter.doFilter()");
            super.doFilter(request, response, chain);
        }
    }

    public static class TestWrapServlet extends ServletHolder.Wrapper
    {
        private EventQueue events;

        public TestWrapServlet(Servlet servlet, EventQueue events)
        {
            super(servlet);
            this.events = events;
        }

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            events.addEvent("TestWrapServlet.init()");
            super.init(config);
        }

        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            events.addEvent("TestWrapServlet.service()");
            super.service(req, res);
        }
    }

    public static class TestWrapListener extends ListenerHolder.Wrapper implements ServletRequestListener
    {
        private ServletRequestListener requestListener;
        private EventQueue events;

        public TestWrapListener(ServletRequestListener listener, EventQueue events)
        {
            super(listener);
            this.requestListener = listener;
            this.events = events;
        }

        @Override
        public void requestDestroyed(ServletRequestEvent sre)
        {
            this.events.addEvent("TestWrapListener.requestDestroyed()");
            requestListener.requestDestroyed(sre);
        }

        @Override
        public void requestInitialized(ServletRequestEvent sre)
        {
            this.events.addEvent("TestWrapListener.requestInitialized()");
            requestListener.requestInitialized(sre);
        }
    }
}
