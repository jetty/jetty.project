//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.toolchain.test.ExtraMatchers.ordered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CrossContextDispatcherFilterTest
{
    private Server server;
    private LocalConnector connector;

    public void startServer(ContextHandlerCollection contextHandlerCollection) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        server.addConnector(connector);

        server.setHandler(contextHandlerCollection);
        server.start();
    }

    @Test
    public void testFilterInitiated() throws Exception
    {
        final CountDownLatch filterCompleteLatch = new CountDownLatch(1);
        final BlockingQueue<String> events = new LinkedBlockingDeque<>();

        // Root Context
        ServletContextHandler contextRoot = new ServletContextHandler();
        contextRoot.setCrossContextDispatchSupported(true);
        contextRoot.setContextPath("/");
        ServletHolder helloServlet = new ServletHolder("hello-servlet", new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                events.add("Hello Servlet GET (context=" + getServletContext().getContextPath() + ")");
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                resp.getWriter().println("Reached HelloServlet");
            }
        });
        contextRoot.addServlet(helloServlet, "*.hello");
        FilterHolder dispatchFilter = new FilterHolder(new Filter()
        {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                HttpServletRequest httpRequest = (HttpServletRequest)request;
                events.add("Reached Filter (context=" + request.getServletContext().getContextPath() + ")");
                ServletContext otherContext = request.getServletContext().getContext("/service");
                RequestDispatcher dispatcher = otherContext.getRequestDispatcher("/alt/foo.hello");
                events.add("Filter Dispatcher Forward (context=" + request.getServletContext().getContextPath() + ")");
                events.add(" + http.requestURI=" + httpRequest.getRequestURI());
                events.add(" + http.requestURL=" + httpRequest.getRequestURL());
                dispatcher.forward(request, response);
                events.add("Filter Returned from Forward Dispatch (context=" + request.getServletContext().getContextPath() + ")");
                events.add(" - http.requestURI=" + httpRequest.getRequestURI());
                events.add(" - http.requestURL=" + httpRequest.getRequestURL());
                filterCompleteLatch.countDown();
            }
        });
        contextRoot.addFilter(dispatchFilter, "/group/*", EnumSet.of(DispatcherType.REQUEST));

        // Service Context
        ServletContextHandler contextService = new ServletContextHandler();
        contextService.setCrossContextDispatchSupported(true);
        contextService.setContextPath("/service");
        ServletHolder serviceHolder = new ServletHolder("service-servlet", new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                events.add("Service Servlet GET (context=" + getServletContext().getContextPath() + ")");
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                resp.getWriter().println("Reached Service context");
            }
        });
        contextService.addServlet(serviceHolder, "/alt/*");

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(contextRoot);
        contextHandlerCollection.addHandler(contextService);

        startServer(contextHandlerCollection);

        String rawRequest = """
            GET /group/formal.hello HTTP/1.1
            Host: local
            Connection: close
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("Reached Service context"));

        assertTrue(filterCompleteLatch.await(5, TimeUnit.SECONDS));
        List<String> expectedEvents = new ArrayList<>();
        expectedEvents.add("Reached Filter (context=)");
        expectedEvents.add("Filter Dispatcher Forward (context=)");
        expectedEvents.add(" + http.requestURI=/group/formal.hello");
        expectedEvents.add(" + http.requestURL=http://local/group/formal.hello");
        expectedEvents.add("Service Servlet GET (context=/service)");
        expectedEvents.add("Filter Returned from Forward Dispatch (context=)");
        expectedEvents.add(" - http.requestURI=/group/formal.hello");
        expectedEvents.add(" - http.requestURL=http://local/group/formal.hello");
        List<String> eventsInOrder = new ArrayList<>(events);
        assertThat(eventsInOrder, ordered(expectedEvents));
    }

    @Test
    public void testFilterInitiatedWithAsync() throws Exception
    {
        final CountDownLatch filterCompleteLatch = new CountDownLatch(1);
        final BlockingQueue<String> events = new LinkedBlockingDeque<>();

        // Root Context
        ServletContextHandler contextRoot = new ServletContextHandler();
        contextRoot.setCrossContextDispatchSupported(true);
        contextRoot.setContextPath("/");
        ServletHolder helloServlet = new ServletHolder("hello-servlet", new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                events.add("Hello Servlet GET (context=" + getServletContext().getContextPath() + ")");
                resp.setCharacterEncoding("utf-8");
                resp.setContentType("text/plain");
                resp.getWriter().println("Reached HelloServlet");
            }
        });
        contextRoot.addServlet(helloServlet, "*.hello");
        FilterHolder dispatchFilter = new FilterHolder(new Filter()
        {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                HttpServletRequest httpRequest = (HttpServletRequest)request;
                events.add("Reached Filter (context=" + request.getServletContext().getContextPath() + ")");
                ServletContext otherContext = request.getServletContext().getContext("/service");
                RequestDispatcher dispatcher = otherContext.getRequestDispatcher("/alt/foo.hello");
                events.add("Filter Dispatcher Forward (context=" + request.getServletContext().getContextPath() + ")");
                events.add(" + http.requestURI=" + httpRequest.getRequestURI());
                events.add(" + http.requestURL=" + httpRequest.getRequestURL());
                dispatcher.forward(request, response);
                events.add("Filter Returned from Forward Dispatch (context=" + request.getServletContext().getContextPath() + ")");
                events.add(" - http.requestURI=" + httpRequest.getRequestURI());
                events.add(" - http.requestURL=" + httpRequest.getRequestURL());
                filterCompleteLatch.countDown();
            }
        });
        contextRoot.addFilter(dispatchFilter, "/group/*", EnumSet.of(DispatcherType.REQUEST));

        // Service Context
        ServletContextHandler contextService = new ServletContextHandler();
        contextService.setCrossContextDispatchSupported(true);
        contextService.setContextPath("/service");
        ServletHolder serviceHolder = new ServletHolder("service-servlet", new HttpServlet()
        {
            static final String ASYNC_FLAG_NAME = "async.flag";

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                if (req.getAttribute(ASYNC_FLAG_NAME) == null)
                {
                    events.add("Service Servlet GET (context=" + getServletContext().getContextPath() + ") startAsync");
                    AsyncContext asyncContext = req.startAsync(req, resp);
                    req.setAttribute(ASYNC_FLAG_NAME, new Object());
                    asyncContext.setTimeout(100);
                    asyncContext.addListener(new AsyncListener()
                    {
                        @Override
                        public void onComplete(AsyncEvent event)
                        {
                        }

                        @Override
                        public void onTimeout(AsyncEvent event)
                        {
                            // trigger redispatch back to this servlet
                            events.add("Async onTimeout predispatch");
                            event.getAsyncContext().dispatch();
                            events.add("Async onTimeout postdispatch");
                        }

                        @Override
                        public void onError(AsyncEvent event)
                        {
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event)
                        {
                        }
                    });
                }
                else
                {
                    events.add("Service Servlet GET (context=" + getServletContext().getContextPath() + ") afterDispatch");
                    resp.setCharacterEncoding("utf-8");
                    resp.setContentType("text/plain");
                    resp.getWriter().println("Reached Service context");
                }
            }
        });
        serviceHolder.setAsyncSupported(true);
        contextService.addServlet(serviceHolder, "/alt/*");

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(contextRoot);
        contextHandlerCollection.addHandler(contextService);

        startServer(contextHandlerCollection);

        String rawRequest = """
            GET /group/formal.hello HTTP/1.1
            Host: local
            Connection: close
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), containsString("Reached Service context"));

        assertTrue(filterCompleteLatch.await(5, TimeUnit.SECONDS));
        List<String> expectedEvents = new ArrayList<>();
        expectedEvents.add("Reached Filter (context=)");
        expectedEvents.add("Filter Dispatcher Forward (context=)");
        expectedEvents.add(" + http.requestURI=/group/formal.hello");
        expectedEvents.add(" + http.requestURL=http://local/group/formal.hello");
        expectedEvents.add("Service Servlet GET (context=/service) startAsync");
        expectedEvents.add("Filter Returned from Forward Dispatch (context=)");
        expectedEvents.add(" - http.requestURI=/group/formal.hello");
        expectedEvents.add(" - http.requestURL=http://local/group/formal.hello");
        expectedEvents.add("Async onTimeout predispatch");
        expectedEvents.add("Async onTimeout postdispatch");
        expectedEvents.add("Service Servlet GET (context=/service) afterDispatch");
        List<String> eventsInOrder = new ArrayList<>(events);
        assertThat(eventsInOrder, ordered(expectedEvents));
    }
}
