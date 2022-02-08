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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                chain.doFilter(request, response);
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
        expectedEvents.add("TestWrapServlet.init()");
        expectedEvents.add("TestWrapListener.requestInitialized()");
        expectedEvents.add("TestWrapFilter.doFilter()");
        expectedEvents.add("TestWrapServlet.service()");
        expectedEvents.add("TestWrapListener.requestDestroyed()");

        List<String> actualEvents = new ArrayList<>();
        actualEvents.addAll(events);

        assertThat("Metrics Events Count", actualEvents.size(), is(expectedEvents.size()));
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
