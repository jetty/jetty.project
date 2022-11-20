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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ErrorPageTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ErrorPageTest.class);

    private Server _server;
    private LocalConnector _connector;
    private StacklessLogging _stackless;
    private static CountDownLatch __asyncSendErrorCompleted;
    private ErrorPageErrorHandler _errorPageErrorHandler;
    private static AtomicBoolean __destroyed;
    private ServletContextHandler _context;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        _context = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);

        _server.setHandler(_context);

        _context.setContextPath("/");
        _context.addFilter(SingleDispatchFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
        _context.addServlet(DefaultServlet.class, "/");
        _context.addServlet(FailServlet.class, "/fail/*");
        _context.addServlet(FailServletDoubleWrap.class, "/fail-double-wrap/*");
        _context.addServlet(FailClosedServlet.class, "/fail-closed/*");
        _context.addServlet(ErrorServlet.class, "/error/*");
        _context.addServlet(AppServlet.class, "/app/*");
        _context.addServlet(LongerAppServlet.class, "/longer.app/*");
        _context.addServlet(SyncSendErrorServlet.class, "/sync/*");
        _context.addServlet(AsyncSendErrorServlet.class, "/async/*");
        _context.addServlet(NotEnoughServlet.class, "/notenough/*");
        _context.addServlet(UnavailableServlet.class, "/unavailable/*");
        _context.addServlet(DeleteServlet.class, "/delete/*");
        _context.addServlet(ErrorAndStatusServlet.class, "/error-and-status/*");
        _context.addServlet(ErrorContentTypeCharsetWriterInitializedServlet.class, "/error-mime-charset-writer/*");

        Handler.Wrapper noopHandler = new Handler.Wrapper()
        {
            @Override
            public Request.Processor handle(Request request) throws Exception
            {
                if (Request.getPathInContext(request).startsWith("/noop"))
                    return null;
                else
                    return super.handle(request);
            }
        };
        _context.insertHandler(noopHandler);

        _errorPageErrorHandler = new ErrorPageErrorHandler();
        _context.setErrorProcessor(_errorPageErrorHandler);
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
    public void testErrorOverridesMimeTypeAndCharset() throws Exception
    {
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /error-mime-charset-writer/ HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Accept: */*\r\n");
        rawRequest.append("Accept-Charset: *\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(595));
        String actualContentType = response.get(HttpHeader.CONTENT_TYPE);
        // should not expect to see charset line from servlet
        assertThat(actualContentType, not(containsString("charset=US-ASCII")));
        String body = response.getContent();

        assertThat(body, containsString("ERROR_PAGE: /595"));
        assertThat(body, containsString("ERROR_MESSAGE: 595"));
        assertThat(body, containsString("ERROR_CODE: 595"));
        assertThat(body, containsString("ERROR_EXCEPTION: null"));
        assertThat(body, containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(body, containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$ErrorContentTypeCharsetWriterInitializedServlet-"));
        assertThat(body, containsString("ERROR_REQUEST_URI: /error-mime-charset-writer/"));
    }

    @Test
    public void testErrorOverridesStatus() throws Exception
    {
        String response = _connector.getResponse("GET /error-and-status/anything HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 594 594"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(response, Matchers.containsString("ERROR_MESSAGE: custom get error"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 594"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$ErrorAndStatusServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /error-and-status/anything"));
    }

    @Test
    public void testHttp204CannotHaveBody() throws Exception
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
    public void testDeleteCannotHaveBody() throws Exception
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
    public void testGenerateAcceptableResponseNoAcceptHeader() throws Exception
    {
        // no global error page here
        _errorPageErrorHandler.getErrorPages().remove(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE);

        String response = _connector.getResponse("GET /fail/code?code=598 HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, Matchers.containsString("<title>Error 598"));
        assertThat(response, Matchers.containsString("<h2>HTTP ERROR 598"));
        assertThat(response, Matchers.containsString("/fail/code"));
    }

    @Test
    public void testGenerateAcceptableResponseHtmlAcceptHeader() throws Exception
    {
        // no global error page here
        _errorPageErrorHandler.getErrorPages().remove(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE);

        // even when text/html is not the 1st content type, a html error page should still be generated
        String response = _connector.getResponse("GET /fail/code?code=598 HTTP/1.0\r\n" +
            "Accept: application/bytes,text/html\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, Matchers.containsString("<title>Error 598"));
        assertThat(response, Matchers.containsString("<h2>HTTP ERROR 598"));
        assertThat(response, Matchers.containsString("/fail/code"));
    }

    @Test
    public void testGenerateAcceptableResponseNoHtmlAcceptHeader() throws Exception
    {
        // no global error page here
        _errorPageErrorHandler.getErrorPages().remove(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE);

        String response = _connector.getResponse("GET /fail/code?code=598 HTTP/1.0\r\n" +
            "Accept: application/bytes\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response, not(Matchers.containsString("<title>Error 598")));
        assertThat(response, not(Matchers.containsString("<h2>HTTP ERROR 598")));
        assertThat(response, not(Matchers.containsString("/fail/code")));
    }

    @Test
    public void testNestedSendErrorDoesNotLoop() throws Exception
    {
        String response = _connector.getResponse("GET /fail/code?code=597 HTTP/1.0\r\n\r\n");
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
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailClosedServlet-"));
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
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/code"));
    }

    @Test
    public void testErrorMessage() throws Exception
    {
        String response = _connector.getResponse("GET /fail/code?code=599&message=FiveNineNine HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
        assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(response, Matchers.containsString("ERROR_MESSAGE: FiveNineNine"));
        assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/code"));
    }

    @Test
    public void testErrorException() throws Exception
    {
        _errorPageErrorHandler.setUnwrapServletException(false);
        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            String response = _connector.getResponse("GET /fail/exception HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: jakarta.servlet.ServletException: java.lang.IllegalStateException: Test Exception"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class jakarta.servlet.ServletException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/exception"));
            response = _connector.getResponse("GET /fail-double-wrap/exception HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: jakarta.servlet.ServletException: jakarta.servlet.ServletException: java.lang.IllegalStateException: Test Exception"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class jakarta.servlet.ServletException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServletDoubleWrap-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail-double-wrap/exception"));
        }

        _errorPageErrorHandler.setUnwrapServletException(true);
        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            String response = _connector.getResponse("GET /fail/exception HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: java.lang.IllegalStateException: Test Exception"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.IllegalStateException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/exception"));
            response = _connector.getResponse("GET /fail-double-wrap/exception HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: java.lang.IllegalStateException: Test Exception"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.IllegalStateException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServletDoubleWrap-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail-double-wrap/exception"));
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
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
    }

    @Test
    public void testGlobalErrorException() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            String response = _connector.getResponse("GET /fail/global?code=NAN HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: java.lang.NumberFormatException: For input string: \"NAN\""));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.NumberFormatException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$FailServlet-"));
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
            assertThat(response, Matchers.containsString("ERROR_MESSAGE: Unable to parse URI query"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 400"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: org.eclipse.jetty.http.BadMessageException: 400: Unable to parse URI query"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: class org.eclipse.jetty.http.BadMessageException"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$AppServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /app"));
            assertThat(response, Matchers.containsString("getParameterMap()= {}"));
        }
    }

    @Test
    public void testAsyncErrorPageDSC() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            String response = _connector.getResponse("GET /async/info?mode=DSC HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$AsyncSendErrorServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /async/info"));
            assertTrue(__asyncSendErrorCompleted.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAsyncErrorPageSDC() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            String response = _connector.getResponse("GET /async/info?mode=SDC HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$AsyncSendErrorServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /async/info"));
            assertTrue(__asyncSendErrorCompleted.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testAsyncErrorPageSCD() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            String response = _connector.getResponse("GET /async/info?mode=SCD HTTP/1.0\r\n\r\n");
            assertThat(response, Matchers.containsString("HTTP/1.1 599 599"));
            assertThat(response, Matchers.containsString("ERROR_PAGE: /599"));
            assertThat(response, Matchers.containsString("ERROR_CODE: 599"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION: null"));
            assertThat(response, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
            assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$AsyncSendErrorServlet-"));
            assertThat(response, Matchers.containsString("ERROR_REQUEST_URI: /async/info"));
            assertTrue(__asyncSendErrorCompleted.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testNoop() throws Exception
    {
        // The ServletContextHandler does not handle so should go to the servers ErrorProcessor.
        String response = _connector.getResponse("GET /noop/info HTTP/1.0\r\n\r\n");
        assertThat(response, Matchers.containsString("HTTP/1.1 404 Not Found"));
        assertThat(response, not(Matchers.containsString("DISPATCH: ERROR")));
        assertThat(response, not(Matchers.containsString("ERROR_PAGE: /GlobalErrorPage")));
        assertThat(response, not(Matchers.containsString("ERROR_CODE: 404")));
        assertThat(response, not(Matchers.containsString("ERROR_EXCEPTION: null")));
        assertThat(response, not(Matchers.containsString("ERROR_EXCEPTION_TYPE: null")));
        assertThat(response, not(Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.DefaultServlet-")));
        assertThat(response, not(Matchers.containsString("ERROR_REQUEST_URI: /noop/info")));
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
        assertThat(response, Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.ErrorPageTest$NotEnoughServlet-"));
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

    @Test
    public void testPermanentlyUnavailable() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(_context.getLogger()))
        {
            try (StacklessLogging ignore2 = new StacklessLogging(ServletChannel.class))
            {
                __destroyed = new AtomicBoolean(false);
                String response = _connector.getResponse("GET /unavailable/info HTTP/1.0\r\n\r\n");
                assertThat(response, Matchers.containsString("HTTP/1.1 404 "));
                _server.stop();
                assertTrue(__destroyed.get());
            }
        }
    }

    @Test
    public void testUnavailable() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(_context.getLogger()))
        {
            try (StacklessLogging ignore2 = new StacklessLogging(ServletChannel.class))
            {
                __destroyed = new AtomicBoolean(false);
                String response = _connector.getResponse("GET /unavailable/info?for=1 HTTP/1.0\r\n\r\n");
                assertThat(response, Matchers.containsString("HTTP/1.1 503 "));
                assertFalse(__destroyed.get());

                response = _connector.getResponse("GET /unavailable/info?ok=true HTTP/1.0\r\n\r\n");
                assertThat(response, Matchers.containsString("HTTP/1.1 503 "));
                assertFalse(__destroyed.get());

                Thread.sleep(1500);

                response = _connector.getResponse("GET /unavailable/info?ok=true HTTP/1.0\r\n\r\n");
                assertThat(response, Matchers.containsString("HTTP/1.1 200 "));
                assertFalse(__destroyed.get());
            }
        }
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
                final String mode = request.getParameter("mode");
                switch (mode)
                {
                    case "DSC":
                    case "SDC":
                    case "SCD":
                        break;
                    default:
                        throw new IllegalStateException(mode);
                }

                final boolean lateComplete = "true".equals(request.getParameter("latecomplete"));
                AsyncContext async = request.startAsync();
                async.start(() ->
                {
                    try
                    {
                        switch (mode)
                        {
                            case "SDC":
                                response.sendError(599);
                                break;
                            case "SCD":
                                response.sendError(599);
                                async.complete();
                                break;
                            default:
                                break;
                        }

                        // Complete after original servlet
                        hold.countDown();

                        // Wait until request async waiting
                        while (ServletContextRequest.getServletContextRequest(request).getServletRequestState().getState() == ServletRequestState.State.HANDLING)
                        {
                            try
                            {
                                Thread.sleep(10);
                            }
                            catch (InterruptedException e)
                            {
                                e.printStackTrace();
                            }
                        }
                        try
                        {
                            switch (mode)
                            {
                                case "DSC":
                                    response.sendError(599);
                                    async.complete();
                                    break;
                                case "SDC":
                                    async.complete();
                                    break;
                                default:
                                    break;
                            }
                        }
                        catch (IllegalStateException e)
                        {
                            LOG.trace("IGNORED", e);
                        }
                        finally
                        {
                            __asyncSendErrorCompleted.countDown();
                        }
                    }
                    catch (IOException e)
                    {
                        LOG.warn("Unable to send error", e);
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
            String message = request.getParameter("message");
            if (code != null)
            {
                if (StringUtil.isBlank(message))
                    response.sendError(Integer.parseInt(code));
                else
                    response.sendError(Integer.parseInt(code), message);
            }
            else
            {
                throw new ServletException(
                    new IllegalStateException(message == null ? "Test Exception" : message));
            }
        }
    }

    public static class FailServletDoubleWrap extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String code = request.getParameter("code");
            if (code != null)
                response.sendError(Integer.parseInt(code));
            else
                throw new ServletException(new ServletException(new IllegalStateException("Test Exception")));
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
                LOG.trace("IGNORED", ignore);
            }
        }
    }

    public static class ErrorContentTypeCharsetWriterInitializedServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/html; charset=US-ASCII");
            PrintWriter writer = response.getWriter();
            writer.println("Intentionally using sendError(595)");
            response.sendError(595);
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

    public static class UnavailableServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String ok = request.getParameter("ok");
            if (Boolean.parseBoolean(ok))
            {
                response.setStatus(200);
                response.flushBuffer();
                return;
            }

            String f = request.getParameter("for");
            if (f == null)
                throw new UnavailableException("testing permanent");

            throw new UnavailableException("testing periodic", Integer.parseInt(f));
        }

        @Override
        public void destroy()
        {
            if (__destroyed != null)
                __destroyed.set(true);
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
