//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Dispatcher;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ErrorPageTest
{
    private Server _server;
    private LocalConnector _connector;
    private StacklessLogging _stackless;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY|ServletContextHandler.NO_SESSIONS);

        _server.addConnector(_connector);
        _server.setHandler(context);

        context.setContextPath("/");

        context.addServlet(DefaultServlet.class, "/");
        context.addServlet(FailServlet.class, "/fail/*");
        context.addServlet(FailClosedServlet.class, "/fail-closed/*");
        context.addServlet(ErrorServlet.class, "/error/*");
        
        ErrorPageErrorHandler error = new ErrorPageErrorHandler();
        context.setErrorHandler(error);
        error.addErrorPage(599,"/error/599");
        error.addErrorPage(IllegalStateException.class.getCanonicalName(),"/error/TestException");
        error.addErrorPage(ErrorPageErrorHandler.GLOBAL_ERROR_PAGE,"/error/GlobalErrorPage");
        
        _server.start();
        _stackless=new StacklessLogging(ServletHandler.class);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _stackless.close();
        _server.stop();
        _server.join();
    }
    
    @Test
    public void testSendErrorClosedResponse() throws Exception
    {
        String response = _connector.getResponse("GET /fail-closed/ HTTP/1.0\r\n\r\n");
        System.out.println(response);
        assertThat(response,Matchers.containsString("HTTP/1.1 599 599"));
        assertThat(response,Matchers.containsString("DISPATCH: ERROR"));
        assertThat(response,Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(response,Matchers.containsString("ERROR_CODE: 599"));
        assertThat(response,Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response,Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response,Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailClosedServlet-"));
        assertThat(response,Matchers.containsString("ERROR_REQUEST_URI: /fail-closed/"));
        
        assertThat(response,not(containsString("This shouldn't be seen")));
    }
    
    @Test
    public void testErrorCode() throws Exception
    {
        String response = _connector.getResponse("GET /fail/code?code=599 HTTP/1.0\r\n\r\n");
        assertThat(response,Matchers.containsString("HTTP/1.1 599 599"));
        assertThat(response,Matchers.containsString("ERROR_PAGE: /599"));
        assertThat(response,Matchers.containsString("ERROR_CODE: 599"));
        assertThat(response,Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response,Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response,Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response,Matchers.containsString("ERROR_REQUEST_URI: /fail/code"));
    }
    
    @Test
    public void testErrorException() throws Exception
    {
        try(StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            String response = _connector.getResponse("GET /fail/exception HTTP/1.0\r\n\r\n");
            assertThat(response,Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response,Matchers.containsString("ERROR_PAGE: /TestException"));
            assertThat(response,Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response,Matchers.containsString("ERROR_EXCEPTION: javax.servlet.ServletException: java.lang.IllegalStateException"));
            assertThat(response,Matchers.containsString("ERROR_EXCEPTION_TYPE: class javax.servlet.ServletException"));
            assertThat(response,Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
            assertThat(response,Matchers.containsString("ERROR_REQUEST_URI: /fail/exception"));
        }
    }
    
    @Test
    public void testGlobalErrorCode() throws Exception
    {
        String response = _connector.getResponse("GET /fail/global?code=598 HTTP/1.0\r\n\r\n");
        assertThat(response,Matchers.containsString("HTTP/1.1 598 598"));
        assertThat(response,Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
        assertThat(response,Matchers.containsString("ERROR_CODE: 598"));
        assertThat(response,Matchers.containsString("ERROR_EXCEPTION: null"));
        assertThat(response,Matchers.containsString("ERROR_EXCEPTION_TYPE: null"));
        assertThat(response,Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
        assertThat(response,Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
    }
    
    @Test
    public void testGlobalErrorException() throws Exception
    {
        try(StacklessLogging stackless = new StacklessLogging(HttpChannel.class))
        {
            String response = _connector.getResponse("GET /fail/global?code=NAN HTTP/1.0\r\n\r\n");
            assertThat(response,Matchers.containsString("HTTP/1.1 500 Server Error"));
            assertThat(response,Matchers.containsString("ERROR_PAGE: /GlobalErrorPage"));
            assertThat(response,Matchers.containsString("ERROR_CODE: 500"));
            assertThat(response,Matchers.containsString("ERROR_EXCEPTION: java.lang.NumberFormatException: For input string: \"NAN\""));
            assertThat(response,Matchers.containsString("ERROR_EXCEPTION_TYPE: class java.lang.NumberFormatException"));
            assertThat(response,Matchers.containsString("ERROR_SERVLET: org.eclipse.jetty.servlet.ErrorPageTest$FailServlet-"));
            assertThat(response,Matchers.containsString("ERROR_REQUEST_URI: /fail/global"));
        }
    }

    public static class FailServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String code=request.getParameter("code");
            if (code!=null)
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
            }
        }
    }
    
    public static class ErrorServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            PrintWriter writer = response.getWriter();
            writer.println("DISPATCH: " + request.getDispatcherType().name());
            writer.println("ERROR_PAGE: " + request.getPathInfo());
            writer.println("ERROR_MESSAGE: " + request.getAttribute(Dispatcher.ERROR_MESSAGE));
            writer.println("ERROR_CODE: " + request.getAttribute(Dispatcher.ERROR_STATUS_CODE));
            writer.println("ERROR_EXCEPTION: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION));
            writer.println("ERROR_EXCEPTION_TYPE: " + request.getAttribute(Dispatcher.ERROR_EXCEPTION_TYPE));
            writer.println("ERROR_SERVLET: " + request.getAttribute(Dispatcher.ERROR_SERVLET_NAME));
            writer.println("ERROR_REQUEST_URI: " + request.getAttribute(Dispatcher.ERROR_REQUEST_URI));
        }
    }
    
}
