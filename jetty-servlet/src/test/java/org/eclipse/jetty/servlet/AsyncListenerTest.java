//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Ignore;
import org.junit.Test;

@Ignore("Not handling Exceptions during Async very well")
public class AsyncListenerTest
{
    // Unique named RuntimeException to help during debugging / assertions
    @SuppressWarnings("serial")
    public static class FooRuntimeException extends RuntimeException
    {
    }

    // Unique named Exception to help during debugging / assertions
    @SuppressWarnings("serial")
    public static class FooException extends Exception
    {
    }

    // Unique named Throwable to help during debugging / assertions
    @SuppressWarnings("serial")
    public static class FooThrowable extends Throwable
    {
    }

    // Unique named Error to help during debugging / assertions
    @SuppressWarnings("serial")
    public static class FooError extends Error
    {
    }

    /**
     * Basic AsyncListener adapter that simply logs (and makes testcase writing easier) 
     */
    public static class AsyncListenerAdapter implements AsyncListener
    {
        private static final Logger LOG = Log.getLogger(AsyncListenerTest.AsyncListenerAdapter.class);

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            LOG.info("onComplete({})",event);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            LOG.info("onTimeout({})",event);
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            LOG.info("onError({})",event);
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            LOG.info("onStartAsync({})",event);
        }
    }

    /**
     * Common ErrorContext for normal and async error handling
     */
    public static class ErrorContext implements AsyncListener
    {
        private static final Logger LOG = Log.getLogger(AsyncListenerTest.ErrorContext.class);

        public void report(Throwable t, ServletRequest req, ServletResponse resp) throws IOException
        {
            if (resp instanceof HttpServletResponse)
            {
                ((HttpServletResponse)resp).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            resp.setContentType("text/plain");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
            PrintWriter out = resp.getWriter();
            t.printStackTrace(out);
        }

        private void reportThrowable(AsyncEvent event) throws IOException
        {
            Throwable t = event.getThrowable();
            if (t == null)
            {
                return;
            }
            ServletRequest req = event.getAsyncContext().getRequest();
            ServletResponse resp = event.getAsyncContext().getResponse();
            report(t,req,resp);
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
            LOG.info("onComplete({})",event);
            reportThrowable(event);
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
            LOG.info("onTimeout({})",event);
            reportThrowable(event);
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            LOG.info("onError({})",event);
            reportThrowable(event);
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
            LOG.info("onStartAsync({})",event);
            reportThrowable(event);
        }
    }

    /**
     * Common filter for all test cases that should handle Errors in a consistent way
     * regardless of how the exception / error occurred in the servlets in the chain.
     */
    public static class ErrorFilter implements Filter
    {
        private final List<ErrorContext> tracking;

        public ErrorFilter(List<ErrorContext> tracking)
        {
            this.tracking = tracking;
        }

        @Override
        public void destroy()
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            ErrorContext err = new ErrorContext();
            tracking.add(err);
            try
            {
                chain.doFilter(request,response);
            }
            catch (Throwable t)
            {
                err.report(t,request,response);
            }
            finally
            {
                if (request.isAsyncStarted())
                {
                    request.getAsyncContext().addListener(err);
                }
            }
        }

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }
    }

    /**
     * Normal non-async testcase of error handling from a filter
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorNoAsync() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                throw new FooRuntimeException();
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, then application Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_Exception() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                req.startAsync();
                // before listeners are added, toss Exception
                throw new FooRuntimeException();
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, add listener that does nothing, then application Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_AddEmptyListener_Exception() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                AsyncContext ctx = req.startAsync();
                ctx.addListener(new AsyncListenerAdapter());
                throw new FooRuntimeException();
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, add listener that completes only, then application Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_AddListener_Exception() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                AsyncContext ctx = req.startAsync();
                ctx.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                        System.err.println("### ONERROR");
                        event.getThrowable().printStackTrace(System.err);
                        event.getAsyncContext().complete();
                    }
                });
                throw new FooRuntimeException();
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, add listener, in onStartAsync throw Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_AddListener_ExceptionDuringOnStart() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                AsyncContext ctx = req.startAsync();
                ctx.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException
                    {
                        throw new FooRuntimeException();
                    }
                });
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }
    
    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, add listener, in onComplete throw Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_AddListener_ExceptionDuringOnComplete() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                AsyncContext ctx = req.startAsync();
                ctx.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                        throw new FooRuntimeException();
                    }
                });
                ctx.complete();
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, add listener, in onTimeout throw Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_AddListener_ExceptionDuringOnTimeout() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                AsyncContext ctx = req.startAsync();
                ctx.setTimeout(1000);
                ctx.addListener(new AsyncListenerAdapter()
                {
                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                        throw new FooRuntimeException();
                    }
                });
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }

    /**
     * async testcase of error handling from a filter.
     * 
     * Async Started, no listener, in start() throw Exception
     * 
     * @throws Exception
     *             on test failure
     */
    @Test
    public void testFilterErrorAsyncStart_NoListener_ExceptionDuringStart() throws Exception
    {
        Server server = new Server();
        LocalConnector conn = new LocalConnector(server);
        conn.setIdleTimeout(10000);
        server.addConnector(conn);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        @SuppressWarnings("serial")
        HttpServlet servlet = new HttpServlet()
        {
            public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                AsyncContext ctx = req.startAsync();
                ctx.setTimeout(1000);
                ctx.start(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        throw new FooRuntimeException();
                    }
                });
            }
        };
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder,"/err/*");
        List<ErrorContext> tracking = new LinkedList<ErrorContext>();
        ErrorFilter filter = new ErrorFilter(tracking);
        context.addFilter(new FilterHolder(filter),"/*",EnumSet.allOf(DispatcherType.class));

        server.setHandler(context);

        try
        {
            server.start();
            String resp = conn.getResponses("GET /err/ HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            assertThat("Response status",resp,containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response",resp,containsString(FooRuntimeException.class.getName()));
        }
        finally
        {
            server.stop();
        }
    }
}
