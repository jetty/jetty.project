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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErrorPageTest
{
    private Server _server;
    private LocalConnector _connector;
    private StacklessLogging _stackless;
    private static CountDownLatch __asyncSendErrorCompleted;
    private ErrorPageErrorHandler _errorPageErrorHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);

        _server.setHandler(context);

        context.setContextPath("/");

        context.addFilter(SingleDispatchFilter.class, "/*", EnumSet.allOf(DispatcherType.class));

        context.addServlet(DefaultServlet.class, "/");
        context.addServlet(FailServlet.class, "/fail/*");
        context.addServlet(FailClosedServlet.class, "/fail-closed/*");
        context.addServlet(ErrorServlet.class, "/error/*");
        context.addServlet(AppServlet.class, "/app/*");
        context.addServlet(LongerAppServlet.class, "/longer.app/*");
        context.addServlet(SyncSendErrorServlet.class, "/sync/*");
        context.addServlet(AsyncSendErrorServlet.class, "/async/*");
        context.addServlet(NotEnoughServlet.class, "/notenough/*");
        context.addServlet(DeleteServlet.class, "/delete/*");
        context.addServlet(ErrorAndStatusServlet.class, "/error-and-status/*");

        HandlerWrapper noopHandler = new HandlerWrapper()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (target.startsWith("/noop"))
                    return;
                else
                    super.handle(target, baseRequest, request, response);
            }
        };
        context.insertHandler(noopHandler);

        _errorPageErrorHandler = new ErrorPageErrorHandler();
        context.setErrorHandler(_errorPageErrorHandler);
        _errorPageErrorHandler.addErrorPage(595, "/error/595");
        _errorPageErrorHandler.addErrorPage(597, "/sync");
        _errorPageErrorHandler.addErrorPage(599, "/error/599");
        _errorPageErrorHandler.addErrorPage(400, "/error/400");
        // error.addErrorPage(500,"/error/500");
        _errorPageErrorHandler.addErrorPage(IllegalStateException.class.getCanonicalName(), "/error/TestException");
        _errorPageErrorHandler.addErrorPage(BadMessageException.class, "/error/BadMessageException");
        _errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");

        _server.start();
        _stackless = new StacklessLogging(ServletHandler.class);

        __asyncSendErrorCompleted = new CountDownLatch(1);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _stackless.close();
        _server.stop();
        _server.join();
    }

    @Test
    void testErrorOverridesStatus() throws Exception
    {
        String response = _connector.getResponse("GET /error-and-status/anything HTTP/1.0\r\n\r\n");
        System.err.println(response);
        assertThat(response, Matchers.containsString("HTTP/1.1 594 594"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(response, Matchers.containsString("ERROR_MESSAGE: custom get error"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 594"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$ErrorAndStatusServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /error-and-status/anything"));
    }

    @Test
    void testHttp204CannotHaveBody() throws Exception
    {
        String response = _connector.getResponse("GET /fail/code?code=204 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 204 No Content"));
        assertThat(response, not(Matchers.containsString("DISPATCH: ")));
        assertThat(response, not(Matchers.containsString("ERROR_PAGE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_CODE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_EXCEPTION: ")));
        assertThat(response, not(Matchers.containsString("ERROR_EXCEPTION_TYPE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_SERVLET: ")));
        assertThat(response, not(Matchers.containsString("ERROR_REQUEST_URI: ")));
    }

    @Test
    void testDeleteCannotHaveBody() throws Exception
    {
        String response = _connector.getResponse("DELETE /delete/anything HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 595 595"));
        assertThat(response, not(Matchers.containsString("DISPATCH: ")));
        assertThat(response, not(Matchers.containsString("ERROR_PAGE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_MESSAGE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_CODE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_EXCEPTION: ")));
        assertThat(response, not(Matchers.containsString("ERROR_EXCEPTION_TYPE: ")));
        assertThat(response, not(Matchers.containsString("ERROR_SERVLET: ")));
        assertThat(response, not(Matchers.containsString("ERROR_REQUEST_URI: ")));

        assertThat(response, not(containsString("This shouldn't be seen")));
    }

    @Test
    void testGenerateAcceptableResponse_noAcceptHeader() throws Exception
    {
        // no global error page here
        _errorPageErrorHandler.getErrorPages().remove(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE);

        String response = _connector.getResponse("GET /fail/code?code=598 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, Matchers.containsString("<title>Error 598 598</title>"));
        assertThat(response, Matchers.containsString("<h2>HTTP ERROR 598</h2>"));
        assertThat(response, Matchers.containsString("Problem accessing /fail/code. Reason:"));
    }

    @Test
    void testGenerateAcceptableResponse_htmlAcceptHeader() throws Exception
    {
        // no global error page here
        _errorPageErrorHandler.getErrorPages().remove(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE);

        // even when text/html is not the 1st content type, a html error page should still be generated
        String response = _connector.getResponse("GET /fail/code?code=598 HTTP/1.0\r\n" +
            "Accept: application/bytes,text/html\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, Matchers.containsString("<title>Error 598 598</title>"));
        assertThat(response, Matchers.containsString("<h2>HTTP ERROR 598</h2>"));
        assertThat(response, Matchers.containsString("Problem accessing /fail/code. Reason:"));
    }

    @Test
    void testGenerateAcceptableResponse_noHtmlAcceptHeader() throws Exception
    {
        // no global error page here
        _errorPageErrorHandler.getErrorPages().remove(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE);

        String response = _connector.getResponse("GET /fail/code?code=598 HTTP/1.0\r\n" +
            "Accept: application/bytes\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, not(Matchers.containsString("<title>Error 598 598</title>")));
        assertThat(response, not(Matchers.containsString("<h2>HTTP ERROR 598</h2>")));
        assertThat(response, not(Matchers.containsString("Problem accessing /fail/code. Reason:")));
    }

    @Test
    void testNestedSendErrorDoesNotLoop() throws Exception
    {
        String response = _connector.getResponse("GET /fail/code?code=597 HTTP/1.0\r\n\r\n");
        System.out.println(response);
        assertThat(response, Matchers.containsString("HTTP/1.1 597 597"));
        assertThat(response, not(Matchers.containsString("time this error page is being accessed")));
    }

    @Test
    public void testSendErrorClosedResponse() throws Exception
    {
        String response = _connector.getResponse("GET /fail-closed/ HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
        assertThat(response, Matchers.containsString("DISPATCH: ERROR"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailClosedServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail-closed/"));

        assertThat(response, not(containsString("This shouldn't be seen")));
    }

    @Test
    public void testErrorCode() throws Exception
    {
        String response = _connector.getResponse("GET /fail/code?code=599 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/code"));
    }

    @Test
    public void testErrorException() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            String response = _connector.getResponse("GET /fail/exception HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: javax.servlet.ServletException: java.lang.IllegalStateException"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class javax.servlet.ServletException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/exception"));
        }
    }

    @Test
    public void testGlobalErrorCode() throws Exception
    {
        String response = _connector.getResponse("GET /fail/global?code=598 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 598"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
    }

    @Test
    public void testGlobalErrorException() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            String response = _connector.getResponse("GET /fail/global?code=NAN HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: java.lang.NumberFormatException: For input string: \"NAN\""));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.NumberFormatException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
        }
    }

    @Test
    public void testBadMessage() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            String response = _connector.getResponse("GET /app?baa=%88%A4 HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 400 Bad Request"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /BadMessageException"));
            assertThat(response, Matchers.containsString("ERROR_MESSAGE: Bad query encoding"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 400"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: org.eclipse.jetty.http.BadMessageException: 400: Bad query encoding"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class org.eclipse.jetty.http.BadMessageException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$AppServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /app"));
            assertThat(response, Matchers.containsString("getParameterMap()= {}"));
        }
    }

    @Test
    @Disabled
    public void testAsyncErrorPage0() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            String response = _connector.getResponse("GET /async/info HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$AsyncSendErrorServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /async/info"));
            assertTrue(__asyncSendErrorCompleted.await(10, TimeUnit.SECONDS));
        }
    }

    // TODO re-enable once async is implemented
    @Test
    @Disabled
    public void testAsyncErrorPage1() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            String response = _connector.getResponse("GET /async/info?latecomplete=true HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$AsyncSendErrorServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /async/info"));
            assertTrue(__asyncSendErrorCompleted.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testNoop() throws Exception
    {
        String response = _connector.getResponse("GET /noop/info HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));
        assertThat(response, Matchers.containsString("DISPATCH: ERROR"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 404"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.DefaultServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /noop/info"));
    }

    @Test
    public void testNotEnough() throws Exception
    {
        String response = _connector.getResponse("GET /notenough/info HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
        assertThat(response, Matchers.containsString("DISPATCH: ERROR"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$NotEnoughServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /notenough/info"));
    }

    @Test
    public void testNotEnoughCommitted() throws Exception
    {
        String response = _connector.getResponse("GET /notenough/info?commit=true HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.containsString("Content-Length: 1000"));
        assertThat(response, Matchers.endsWith("SomeBytes"));
    }

    public static class AppServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            request.getRequestDispatcher("/longer.app/").forward(request, response);
        }
    }

    public static class LongerAppServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            PrintWriter writer = response.getWriter();
            writer.println(request.getRequestURI());
        }
    }

    public static class SyncSendErrorServlet extends HttpServlet implements Servlet
    {
        public static final AtomicInteger COUNTER = new AtomicInteger();

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            int count = COUNTER.incrementAndGet();

            PrintWriter writer = response.getWriter();
            writer.println("this is the " + count + " time this error page is being accessed");
            response.sendError(597, "loop #" + count);
        }
    }

    public static class AsyncSendErrorServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            try
            {
                final CountDownLatch hold = new CountDownLatch(1);
                final boolean lateComplete = "true".equals(request.getParameter("latecomplete"));
                AsyncContext async = request.startAsync();
                async.start(() ->
                {
                    try
                    {
                        response.sendError(599);

                        if (lateComplete)
                        {
                            // Complete after original servlet
                            hold.countDown();
                            // Wait until request is recycled
                            while (Request.getBaseRequest(request).getMetaData() != null)
                            {
                                try
                                {
                                    Thread.sleep(100);
                                }
                                catch (InterruptedException e)
                                {
                                    e.printStackTrace();
                                }
                            }
                            async.complete();
                            __asyncSendErrorCompleted.countDown();
                        }
                        else
                        {
                            // Complete before original servlet
                            try
                            {
                                async.complete();
                                __asyncSendErrorCompleted.countDown();
                            }
                            finally
                            {
                                hold.countDown();
                            }
                        }
                    }
                    catch (IOException e)
                    {
                        Log.getLog().warn(e);
                    }
                });
                hold.await();
            }
            catch (InterruptedException e)
            {
                throw new ServletException(e);
            }
        }
    }

    public static class FailServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String code = request.getParameter("code");
            if (code != null)
                response.sendError(Integer.parseInt(code));
            else
                throw new ServletException(new IllegalStateException());
        }
    }

    public static class FailClosedServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.sendError(599);
            // The below should result in no operation, as response should be closed.
            try
            {
                response.setStatus(200); // this status code should not be seen
                response.getWriter().append("This shouldn't be seen");
            }
            catch (Throwable ignore)
            {
                Log.getLog().ignore(ignore);
            }
        }
    }

    public static class ErrorAndStatusServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.sendError(594, "custom get error");
            response.setStatus(200);
        }
    }

    public static class DeleteServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.getWriter().append("This shouldn't be seen");
            response.sendError(595, "custom delete");
        }
    }

    public static class NotEnoughServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentLength(1000);
            response.getOutputStream().write("SomeBytes".getBytes(StandardCharsets.UTF_8));
            if (Boolean.parseBoolean(request.getParameter("commit")))
                response.flushBuffer();
        }
    }

    public static class ErrorServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getDispatcherType() != DispatcherType.ERROR && request.getDispatcherType() != DispatcherType.ASYNC)
                throw new IllegalStateException("Bad Dispatcher Type " + request.getDispatcherType());

            PrintWriter writer = response.getWriter();
            writer.println("DISPATCH: " + request.getDispatcherType().name());
            writer.println("ERROR_PAGE: " + request.getPathInfo());
            writer.println("ERROR_MESSAGE: " + request.getAttribute(Dispatcher.ERROR_MESSAGE));
            writer.println("ERROR_CODE: " + request.getAttribute(Dispatcher.ERROR_STATUS_CODE));
            writer.println("ERROR_EXCEPTION: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION));
            writer.println("ERROR_EXCEPTION_TYPE: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION_TYPE));
            writer.println("ERROR_SERVLET: " + request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
            writer.println("ERROR_REQUEST_URI: " + request.getAttribute(Dispatcher.ERROR_REQUEST_URI));
            writer.println("getParameterMap()= " + request.getParameterMap());
        }
    }

    public static class SingleDispatchFilter implements Filter
    {
        ConcurrentMap<Integer, Thread> dispatches = new ConcurrentHashMap<>();

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {

        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            final Integer key = request.hashCode();
            Thread current = Thread.currentThread();
            final Thread existing = dispatches.putIfAbsent(key, current);
            if (existing != null && existing != current)
            {
                System.err.println("DOUBLE DISPATCH OF REQUEST!!!!!!!!!!!!!!!!!!");
                System.err.println("Thread " + existing + " :");
                for (StackTraceElement element : existing.getStackTrace())
                {
                    System.err.println("\tat " + element);
                }
                IllegalStateException ex = new IllegalStateException();
                ex.printStackTrace();
                response.flushBuffer();
                throw ex;
            }

            try
            {
                chain.doFilter(request, response);
            }
            finally
            {
                if (existing == null)
                {
                    if (!dispatches.remove(key, current))
                        throw new IllegalStateException();
                }
            }
        }

        @Override
        public void destroy()
        {

        }
    }
}
