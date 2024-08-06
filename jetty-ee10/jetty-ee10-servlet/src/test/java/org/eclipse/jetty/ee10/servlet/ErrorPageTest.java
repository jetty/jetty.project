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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
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
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ErrorPageTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ErrorPageTest.class);

    private Server _server;
    private LocalConnector _connector;

    private void startServer(ServletContextHandler servletContextHandler) throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        // Add regression test for double dispatch
        servletContextHandler.addFilter(EnsureSingleDispatchFilter.class, "/*", EnumSet.allOf(DispatcherType.class));
        _server.setHandler(servletContextHandler);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        // _stackless.close();
        _server.stop();
        _server.join();
    }

    @Test
    public void testErrorOverridesMimeTypeAndCharset() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet errorContentServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentType("text/html; charset=US-ASCII");
                PrintWriter writer = response.getWriter();
                writer.println("Intentionally using sendError(595)");
                response.sendError(595);
            }
        };

        contextHandler.addServlet(errorContentServlet, "/error-mime-charset-writer/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);
        errorPageErrorHandler.addErrorPage(595, "/error/595");

        startServer(contextHandler);

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
        assertThat(response.get(HttpHeader.DATE), notNullValue());
        String actualContentType = response.get(HttpHeader.CONTENT_TYPE);
        // should not expect to see charset line from servlet
        assertThat(actualContentType, not(containsString("charset=US-ASCII")));
        String body = response.getContent();

        assertThat(body, containsString("ERROR_PAGE: /595"));
        assertThat(body, containsString("ERROR_MESSAGE: 595"));
        assertThat(body, containsString("ERROR_CODE: 595"));
        assertThat(body, containsString("ERROR_EXCEPTION: null"));
        assertThat(body, containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(body, containsString("ERROR_SERVLET: " + errorContentServlet.getClass().getName()));
        assertThat(body, containsString("ERROR_REQUEST_URI: /error-mime-charset-writer/"));
    }

    @Test
    public void testErrorOverridesStatus() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet errorAndStatusServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(594, "custom get error");
                response.setStatus(200);
            }
        };

        contextHandler.addServlet(errorAndStatusServlet, "/error-and-status/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");


        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /error-and-status/anything HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Accept: */*\r\n");
        rawRequest.append("Accept-Charset: *\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(594));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(responseBody, Matchers.containsString("ERROR_MESSAGE: custom get error"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 594"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + errorAndStatusServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /error-and-status/anything"));
    }

    /**
     * A 204 Response cannot contain a Body.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.3">RFC7230: Section 3.3</a>
     */
    @Test
    public void testHttp204CannotHaveBody() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(204);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/204");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/204 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Accept: */*\r\n");
        rawRequest.append("Accept-Charset: *\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(204));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat("A 204 Response cannot have a body", responseBody.length(), is(0));
    }

    /**
     * DELETE responses should not have a response body.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.3.5">RFC7231: Section 4.3.5</a>
     */
    @Test
    public void testDeleteCannotHaveBody() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet deleteServlet = new HttpServlet()
        {
            @Override
            protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.getWriter().append("This shouldn't be seen");
                response.sendError(595, "custom delete");
            }
        };

        contextHandler.addServlet(deleteServlet, "/delete/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);
        errorPageErrorHandler.addErrorPage(595, "/error/595");

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("DELETE /delete/anything HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Accept: */*\r\n");
        rawRequest.append("Accept-Charset: *\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(595));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat("A response on DELETE cannot have a body", responseBody.length(), is(0));
    }

    @Test
    public void testGenerateAcceptableResponseNoAcceptHeader() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(598);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/598");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/598 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        // No `Accept` header present
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(598));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("<title>Error 598"));
        assertThat(responseBody, Matchers.containsString("<h2>HTTP ERROR 598"));
        assertThat(responseBody, Matchers.containsString("/fail/598"));
    }

    @Test
    public void testGenerateAcceptableResponseHtmlAcceptHeader() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(598);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/598");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/598 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Accept: application/bytes,text/html\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(598));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        // even when text/html is not the 1st content type, a html error page should still be generated
        assertThat(responseBody, Matchers.containsString("<title>Error 598"));
        assertThat(responseBody, Matchers.containsString("<h2>HTTP ERROR 598"));
        assertThat(responseBody, Matchers.containsString("/fail/598"));
    }

    @Test
    public void testGenerateAcceptableResponseNoHtmlAcceptHeader() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(598);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/598");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/598 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Accept: application/bytes\r\n"); // has an accept header, but not one supported by default in Jetty
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(598));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat("No acceptable response capable of being generated, should result in no body", responseBody.length(), is(0));
    }

    @Test
    public void testNestedSendErrorDoesNotLoop() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet syncSendErrorServlet = new HttpServlet()
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
        };

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(597);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/597");
        contextHandler.addServlet(syncSendErrorServlet, "/sync/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(597, "/sync/");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/597 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(597));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat(responseBody, not(Matchers.containsString("time this error page is being accessed")));
    }

    @Test
    public void testSendErrorClosedResponse() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(599);
                // The below should result in no operation, as response should be closed.

                try
                {
                    response.setStatus(200); // this status code should not be seen
                    response.getWriter().append("This shouldn't be seen");
                    response.addIntHeader("BadHeader", 1234);
                }
                catch (Throwable ignore)
                {
                    LOG.trace("IGNORED", ignore);
                }
            }
        };

        contextHandler.addServlet(failServlet, "/fail-closed/");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail-closed/ HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(599));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("DISPATCH: ERROR"));
        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail-closed/"));

        assertThat(responseBody, not(containsString("This shouldn't be seen")));
        assertThat(responseBody, not(containsString("BadHeader")));
    }

    @Test
    public void testFailResetBufferAfterCommit() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.getWriter().println("Some content");
                response.flushBuffer(); //cause a commit

                assertThrows(IllegalStateException.class,
                    () -> response.resetBuffer(), // this should throw
                    "Reset after response committed");
            }
        };

        contextHandler.addServlet(failServlet, "/fail-reset-buffer/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail-reset-buffer/foo HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200)); // request should pass successfully
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat(responseBody, containsString("Some content"));
    }

    @Test
    public void testCommitSendError() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.getWriter().append("Response committed");
                response.flushBuffer();

                assertThrows(IllegalStateException.class,
                    () -> response.sendError(599), // this should throw
                    "Cannot sendError after commit");
            }
        };

        contextHandler.addServlet(failServlet, "/fail-senderror-after-commit/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail-senderror-after-commit/ HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200)); // request should pass successfully
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat(responseBody, Matchers.containsString("Response committed"));
    }

    @Test
    public void testErrorCodeWithParameter() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(599);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/599");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599?code=param");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/599?value=zed HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(599));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail/599"));
        assertThat(responseBody, Matchers.containsString("getQueryString()=[code=param]"));
        assertThat(responseBody, Matchers.containsString("getRequestURL()=[http://test/error/599?code=param]"));
        assertThat(responseBody, Matchers.containsString("getParameterMap().size=2"));
        assertThat(responseBody, Matchers.containsString("getParameterMap()[code]=[param]"));
        assertThat(responseBody, Matchers.containsString("getParameterMap()[value]=[zed]"));
    }

    @Test
    public void testErrorCodeWithWhiteSpaceOnlyQuery() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(599);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/599");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/599?++++ HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(599));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail/599"));
        assertThat(responseBody, Matchers.containsString("getQueryString()=[++++]"));
        assertThat(responseBody, Matchers.containsString("getParameterMap().size=1"));
        assertThat(responseBody, Matchers.containsString("getParameterMap()[    ]=[]"));
    }

    @Test
    public void testErrorCode() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(599);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/599");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/599 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(599));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail/599"));
    }

    @Test
    public void testErrorCodeNoDefaultServletNonExistentErrorLocation() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.getServletHandler().setEnsureDefaultServlet(false);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(599);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/599");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/doesnotexist");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);
        assertNull(contextHandler.getServletHandler().getMatchedServlet("/doesnotexist"), "Context should not have a default servlet");

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/599 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(599));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("<h2>HTTP ERROR 599</h2>"));
        assertThat(responseBody, Matchers.containsString("<th>SERVLET:</th><td>%s".formatted(failServlet.getClass().getName())));
    }

    @Test
    public void testErrorMessage() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(599, "FiveNineNine");
            }
        };

        contextHandler.addServlet(failServlet, "/fail/599");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/599 HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(599));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 599"));
        assertThat(responseBody, Matchers.containsString("ERROR_MESSAGE: FiveNineNine"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail/599"));
    }

    @ParameterizedTest
    @CsvSource({
        "true,/fail/exception",
        "true,/fail-double-wrap/exception",
        "false,/fail/exception",
        "false,/fail-double-wrap/exception",
    })
    public void testErrorException(boolean unwrapServletException, String path) throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                throw new ServletException(new IllegalStateException("Test Exception"));
            }
        };

        HttpServlet failDoubleWrapServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                throw new ServletException(new ServletException(new IllegalStateException("Test Exception")));
            }
        };

        contextHandler.addServlet(failServlet, "/fail/*");
        contextHandler.addServlet(failDoubleWrapServlet, "/fail-double-wrap/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(IllegalStateException.class.getCanonicalName(), "/error/TestException");
        errorPageErrorHandler.setUnwrapServletException(unwrapServletException);
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET ").append(path).append(" HTTP/1.1\r\n");
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(500));
            assertThat(response.get(HttpHeader.DATE), notNullValue());

            String responseBody = response.getContent();

            boolean isDoubleWrapped = path.contains("-double-");

            // common expectations
            assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(responseBody, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: " + path));

            if (unwrapServletException)
            {
                // with unwrap exceptions turned on
                assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: java.lang.IllegalStateException: Test Exception"));
                assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.IllegalStateException"));
            }
            else
            {
                // with unwrap exceptions turned off
                String expectedException = "jakarta.servlet.ServletException: java.lang.IllegalStateException: Test Exception";
                if (isDoubleWrapped)
                    expectedException = "jakarta.servlet.ServletException: " + expectedException;
                assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: " + expectedException));
                assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: class jakarta.servlet.ServletException"));
            }

            if (isDoubleWrapped)
            {
                // double wrapped throwable expectations
                assertThat(responseBody, Matchers.containsString("ERROR_MESSAGE: jakarta.servlet.ServletException: jakarta.servlet.ServletException: java.lang.IllegalStateException: Test Exception"));
            }
            else
            {
                // single throwable expectations
                assertThat(responseBody, Matchers.containsString("ERROR_MESSAGE: jakarta.servlet.ServletException: java.lang.IllegalStateException: Test Exception"));
            }
        }
    }

    @Test
    public void testGlobalErrorCode() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.sendError(598);
            }
        };

        contextHandler.addServlet(failServlet, "/fail/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /fail/global HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(598));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();

        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 598"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
    }

    @Test
    public void testGlobalErrorException() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet failServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                int nan = Integer.parseInt("NAN"); // should trigger java.lang.NumberFormatException
            }
        };

        contextHandler.addServlet(failServlet, "/fail/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET /fail/global HTTP/1.1\r\n");
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(500));
            assertThat(response.get(HttpHeader.DATE), notNullValue());

            String responseBody = response.getContent();

            assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
            assertThat(responseBody, Matchers.containsString("ERROR_CODE: 500"));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: java.lang.NumberFormatException: For input string: \"NAN\""));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.NumberFormatException"));
            assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + failServlet.getClass().getName()));
            assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
        }
    }

    @Test
    public void testBadMessage() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet appServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                request.getRequestDispatcher("/longer.app/").forward(request, response);
            }
        };

        HttpServlet longerAppServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                request.getParameterMap(); // should trigger a BadMessageException
                PrintWriter writer = response.getWriter();
                writer.println(request.getRequestURI());
            }
        };

        contextHandler.addServlet(appServlet, "/app");
        contextHandler.addServlet(longerAppServlet, "/longer.app/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(BadMessageException.class, "/error/BadMessageException");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET /app?baa=%88%A4 HTTP/1.1\r\n");
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(400));
            assertThat(response.get(HttpHeader.DATE), notNullValue());

            String responseBody = response.getContent();
            assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /BadMessageException"));
            assertThat(responseBody, Matchers.containsString("ERROR_MESSAGE: Unable to parse URI query"));
            assertThat(responseBody, Matchers.containsString("ERROR_CODE: 400"));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: org.eclipse.jetty.http.BadMessageException: 400: Unable to parse URI query"));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: class org.eclipse.jetty.http.BadMessageException"));
            assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + appServlet.getClass().getName()));
            assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /app"));
            assertThat(responseBody, Matchers.containsString("getQueryString()=[baa=%88%A4]"));
            assertThat(responseBody, Matchers.containsString("getParameterMap().size=0"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DSC", "SDC", "SCD"
    })
    public void testAsyncErrorPage(String mode) throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        final CountDownLatch asyncSendErrorCompleted = new CountDownLatch(1);

        HttpServlet asyncServlet = new HttpServlet()
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
                            while (ServletContextRequest.getServletContextRequest(request).getServletRequestState().getState() == ServletChannelState.State.HANDLING)
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
                                asyncSendErrorCompleted.countDown();
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
        };

        contextHandler.addServlet(asyncServlet, "/async/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(599, "/error/599");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging ignore = new StacklessLogging(Dispatcher.class))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET /async/info?mode=%s HTTP/1.1\r\n".formatted(mode));
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(599));
            assertThat(response.get(HttpHeader.DATE), notNullValue());

            String responseBody = response.getContent();

            assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /599"));
            assertThat(responseBody, Matchers.containsString("ERROR_CODE: 599"));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
            assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + asyncServlet.getClass().getName()));
            assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /async/info"));
            assertTrue(asyncSendErrorCompleted.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testNoop() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet appServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.getWriter().println("Got to the App");
            }
        };

        contextHandler.addServlet(appServlet, "/app");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        Handler.Singleton noopHandler = new Handler.Wrapper()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                if (Request.getPathInContext(request).startsWith("/noop"))
                    return false;
                return super.handle(request, response, callback);
            }
        };
        contextHandler.insertHandler(noopHandler);

        startServer(contextHandler);

        // The ServletContextHandler does not handle so should go to the servers ErrorHandler.
        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /noop/info HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(404));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat(responseBody, not(Matchers.containsString("DISPATCH: ERROR")));
        assertThat(responseBody, not(Matchers.containsString("ERROR_PAGE: /GlobalErrorPage")));
        assertThat(responseBody, not(Matchers.containsString("ERROR_CODE: 404")));
        assertThat(responseBody, not(Matchers.containsString("ERROR_EXCEPTION: null")));
        assertThat(responseBody, not(Matchers.containsString("ERROR_EXCEPTION_TYPE: null")));
        assertThat(responseBody, not(Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.ee10.servlet.DefaultServlet-")));
        assertThat(responseBody, not(Matchers.containsString("ERROR_REQUEST_URI: /noop/info")));
    }

    @Test
    public void testNotEnough() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet notEnoughServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentLength(1000);
                response.getOutputStream().write("SomeBytes".getBytes(StandardCharsets.UTF_8));
            }
        };

        contextHandler.addServlet(notEnoughServlet, "/notenough/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /notenough/info HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(500));
        assertThat(response.get(HttpHeader.DATE), notNullValue());

        String responseBody = response.getContent();
        assertThat(responseBody, Matchers.containsString("DISPATCH: ERROR"));
        assertThat(responseBody, Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(responseBody, Matchers.containsString("ERROR_CODE: 500"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(responseBody, Matchers.containsString("ERROR_SERVLET: " + notEnoughServlet.getClass().getName()));
        assertThat(responseBody, Matchers.containsString("ERROR_REQUEST_URI: /notenough/info"));
    }

    @Test
    public void testNotEnoughCommitted() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet notEnoughServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentLength(1000);
                response.getOutputStream().write("SomeBytes".getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            }
        };

        contextHandler.addServlet(notEnoughServlet, "/notenough/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /notenough/commit HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = _connector.getResponse(rawRequest.toString());

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertThat(response.get(HttpHeader.DATE), notNullValue());
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("1000"));

        String responseBody = response.getContent();
        assertThat(responseBody, Matchers.endsWith("SomeBytes"));
    }

    @Test
    public void testPermanentlyUnavailable() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        AtomicBoolean destroyed = new AtomicBoolean(false);

        HttpServlet unavailableServlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                throw new UnavailableException("testing permanent");
            }

            @Override
            public void destroy()
            {
                if (destroyed != null)
                    destroyed.set(true);
            }
        };

        contextHandler.addServlet(unavailableServlet, "/unavailable/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging ignore = new StacklessLogging(contextHandler.getLogger(), LoggerFactory.getLogger(ServletChannel.class)))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET /unavailable/info HTTP/1.1\r\n");
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(404));
            assertThat(response.get(HttpHeader.DATE), notNullValue());
            _server.stop();
            assertTrue(destroyed.get());
        }
    }

    @Test
    public void testUnavailable() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        AtomicBoolean destroyed = new AtomicBoolean(false);

        HttpServlet unavailableServlet = new HttpServlet()
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
                if (destroyed != null)
                    destroyed.set(true);
            }
        };

        contextHandler.addServlet(unavailableServlet, "/unavailable/*");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging ignore = new StacklessLogging(contextHandler.getLogger(), LoggerFactory.getLogger(ServletChannel.class)))
        {
            String request = "GET /unavailable/info?for=1 HTTP/1.0\r\n\r\n";
            HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
            assertThat(response.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE_503));
            assertFalse(destroyed.get());

            request = "GET /unavailable/info?ok=true HTTP/1.0\r\n\r\n";
            response = HttpTester.parseResponse(_connector.getResponse(request));
            assertThat(response.getStatus(), is(HttpStatus.SERVICE_UNAVAILABLE_503));
            assertFalse(destroyed.get());

            Thread.sleep(1500);

            request = "GET /unavailable/info?ok=true HTTP/1.0\r\n\r\n";
            response = HttpTester.parseResponse(_connector.getResponse(request));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertFalse(destroyed.get());
        }
    }

    @Test
    public void testNonUnwrappedMatchExceptionWithErrorPage() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        HttpServlet exceptionServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                throw new TestServletException(new TestException("error page invoked"));
            }
        };

        contextHandler.addServlet(exceptionServlet, "/exception-servlet");
        contextHandler.addServlet(ErrorDumpServlet.class, "/error/*");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE, "/error/GlobalErrorPage");
        errorPageErrorHandler.addErrorPage(TestServletException.class, "/error");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET /exception-servlet HTTP/1.1\r\n");
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(500));
            assertThat(response.get(HttpHeader.DATE), notNullValue());

            String responseBody = response.getContent();
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION: org.eclipse.jetty.ee10.servlet.ErrorPageTest$TestServletException"));
            assertThat(responseBody, Matchers.containsString("ERROR_EXCEPTION_TYPE: class org.eclipse.jetty.ee10.servlet.ErrorPageTest$TestServletException"));
        }
    }

    /**
     * Test to ensure we can redirect an error to an HTML page
     */
    @Test
    public void testErrorHtmlPage(WorkDir workDir) throws Exception
    {
        Path docroot = workDir.getEmptyPathDir();
        Path html500 = docroot.resolve("500.html");
        Files.writeString(html500, "<h1>This is the 500 HTML</h1>");

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);
        contextHandler.setContextPath("/");
        contextHandler.setBaseResource(resourceFactory.newResource(docroot));
        ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
        contextHandler.addServlet(defaultHolder, "/");

        HttpServlet exceptionServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                throw new ServletException("This exception was thrown for testing purposes by testErrorPage test. It is not a bug!");
            }
        };

        contextHandler.addServlet(exceptionServlet, "/exception-servlet");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(500, "/500.html");
        contextHandler.setErrorHandler(errorPageErrorHandler);

        startServer(contextHandler);

        try (StacklessLogging stackless = new StacklessLogging(ServletChannel.class))
        {
            StringBuilder rawRequest = new StringBuilder();
            rawRequest.append("GET /exception-servlet HTTP/1.1\r\n");
            rawRequest.append("Host: test\r\n");
            rawRequest.append("Connection: close\r\n");
            rawRequest.append("\r\n");

            String rawResponse = _connector.getResponse(rawRequest.toString());

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(500));
            assertThat(response.get(HttpHeader.DATE), notNullValue());

            String responseBody = response.getContent();
            assertThat(responseBody, Matchers.containsString("<h1>This is the 500 HTML</h1>"));
        }
    }

    @Test
    public void testErrorHandlerCallsStartAsync() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler("/");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(598, "/error/598");
        context.setErrorHandler(errorPageErrorHandler);

        HttpServlet appServlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.sendError(598);
            }
        };
        context.addServlet(appServlet, "/async/*");
        HttpServlet error598Servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                AsyncContext asyncContext = req.startAsync();
                asyncContext.start(() ->
                {
                    try
                    {
                        int originalStatus = resp.getStatus();
                        resp.setStatus(599);
                        ServletOutputStream output = resp.getOutputStream();
                        output.print("ORIGINAL STATUS CODE: " + originalStatus);
                        asyncContext.complete();
                    }
                    catch (Throwable x)
                    {
                        asyncContext.complete();
                    }
                });
            }
        };
        context.addServlet(error598Servlet, "/error/598");

        startServer(context);

        String request = """
            GET /async/ HTTP/1.1
            Host: localhost
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat(response.getStatus(), is(599));
        String responseBody = response.getContent();
        assertThat(responseBody, containsString("ORIGINAL STATUS CODE: 598"));
    }

    @Test
    public void testErrorHandlerCallsSendError() throws Exception
    {
        ServletContextHandler context = new ServletContextHandler("/");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        errorPageErrorHandler.addErrorPage(597, "/error/597");
        errorPageErrorHandler.addErrorPage(598, "/error/598");
        context.setErrorHandler(errorPageErrorHandler);

        HttpServlet appServlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.sendError(597);
            }
        };
        context.addServlet(appServlet, "/async/*");
        HttpServlet error597Servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.sendError(598);
            }
        };
        context.addServlet(error597Servlet, "/error/597");
        // Cannot land on an error page from another
        // error page, so this Servlet is never called.
        HttpServlet error598Servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                resp.setStatus(599);
            }
        };
        context.addServlet(error598Servlet, "/error/598");

        startServer(context);

        String request = """
            GET /async/ HTTP/1.1
            Host: localhost
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));

        assertThat(response.getStatus(), is(598));
    }

    public static class ErrorDumpServlet extends HttpServlet
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

            writer.printf("getRequestURI()=%s%n", valueOf(request.getRequestURI()));
            writer.printf("getRequestURL()=%s%n", valueOf(request.getRequestURL()));
            writer.printf("getQueryString()=%s%n", valueOf(request.getQueryString()));
            Map<String, String[]> params = request.getParameterMap();
            writer.printf("getParameterMap().size=%d%n", params.size());
            for (Map.Entry<String, String[]> entry : params.entrySet())
            {
                String value = null;
                if (entry.getValue() != null)
                {
                    value = String.join(", ", entry.getValue());
                }
                writer.printf("getParameterMap()[%s]=%s%n", entry.getKey(), valueOf(value));
            }
        }

        private String valueOf(Object obj)
        {
            if (obj == null)
                return "null";
            return valueOf(obj.toString());
        }

        private String valueOf(String str)
        {
            if (str == null)
                return "null";
            return String.format("[%s]", str);
        }
    }

    /**
     * Regression test to ensure that double-dispatch does not occur.
     */
    public static class EnsureSingleDispatchFilter implements Filter
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

    private static class TestException extends Exception
    {
        public TestException(String message)
        {
            super(message);
        }
    }

    public static class TestServletException extends ServletException
    {
        public TestServletException(Throwable rootCause)
        {
            super(rootCause);
        }
    }
}
