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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
public class DispatcherTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DispatcherTest.class);

    private Server _server;
    private LocalConnector _connector;
    private ContextHandlerCollection _contextCollection;
    private ServletContextHandler _contextHandler;
    private ResourceHandler _resourceHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);

        _contextCollection = new ContextHandlerCollection();
        _contextHandler = new ServletContextHandler();
        _contextHandler.setContextPath("/context");
        _contextCollection.addHandler(_contextHandler);
        _resourceHandler = new ResourceHandler();
        _resourceHandler.setBaseResource(MavenTestingUtils.getTestResourceDir("dispatchResourceTest").toPath().toAbsolutePath());
        _resourceHandler.setPathInfoOnly(true);
        ContextHandler resourceContextHandler = new ContextHandler("/resource");
        resourceContextHandler.setHandler(_resourceHandler);
        _contextCollection.addHandler(resourceContextHandler);
        _server.setHandler(_contextCollection);
        _server.addConnector(_connector);

        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testForward() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertForwardServlet.class, "/AssertForwardServlet/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "FORWARD";

        String responses = _connector.getResponse("GET /context/ForwardServlet?do=assertforward&do=more&test=1 HTTP/1.0\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testForwardNonUTF8() throws Exception
    {
        _contextHandler.addServlet(ForwardNonUTF8Servlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertNonUTF8ForwardServlet.class, "/AssertForwardServlet/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "FORWARD";
        String responses = _connector.getResponse("GET /context/ForwardServlet?do=assertforward&foreign=%d2%e5%ec%ef%e5%f0%e0%f2%f3%f0%e0&test=1 HTTP/1.0\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testForwardWithParam() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(EchoURIServlet.class, "/EchoURI/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 54\r\n" +
                "\r\n" +
                "/context\r\n" +
                "/EchoURI\r\n" +
                "/x x\r\n" +
                "/context/EchoURI/x%20x;a=1\r\n";

        String responses = _connector.getResponse("GET /context/ForwardServlet;ignore=true?do=req.echo&uri=EchoURI%2Fx%2520x%3Ba=1%3Fb=2 HTTP/1.0\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testNamedForward() throws Exception
    {
        _contextHandler.addServlet(NamedForwardServlet.class, "/forward/*");
        String echo = _contextHandler.addServlet(EchoURIServlet.class, "/echo/*").getName();

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 62\r\n" +
                "\r\n" +
                "/context\r\n" +
                "/forward\r\n" +
                "/info\r\n" +
                "/context/forward/info;param=value\r\n";
        String responses = _connector.getResponse("GET /context/forward/info;param=value?name=" + echo + " HTTP/1.0\n\n");
        assertEquals(expected, responses);
    }

    @Test
    public void testNamedInclude() throws Exception
    {
        _contextHandler.addServlet(NamedIncludeServlet.class, "/include/*");
        String echo = _contextHandler.addServlet(EchoURIServlet.class, "/echo/*").getName();

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 62\r\n" +
                "\r\n" +
                "/context\r\n" +
                "/include\r\n" +
                "/info\r\n" +
                "/context/include/info;param=value\r\n";
        String responses = _connector.getResponse("GET /context/include/info;param=value?name=" + echo + " HTTP/1.0\n\n");
        assertEquals(expected, responses);
    }

    @Test
    public void testForwardWithBadParams() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expect Not valid UTF8 warnings...");
            _contextHandler.addServlet(AlwaysForwardServlet.class, "/forward/*");
            _contextHandler.addServlet(EchoServlet.class, "/echo/*");

            String response;

            response = _connector.getResponse("GET /context/forward/?echo=allgood HTTP/1.0\n\n");
            assertThat(response, containsString(" 200 OK"));
            assertThat(response, containsString("allgood"));

            response = _connector.getResponse("GET /context/forward/params?echo=allgood HTTP/1.0\n\n");
            assertThat(response, containsString(" 200 OK"));
            assertThat(response, containsString("allgood"));
            assertThat(response, containsString("forward"));

            response = _connector.getResponse("GET /context/forward/badparams?echo=badparams HTTP/1.0\n\n");
            assertThat(response, containsString(" 500 "));

            response = _connector.getResponse("GET /context/forward/?echo=badclient&bad=%88%A4 HTTP/1.0\n\n");
            assertThat(response, containsString(" 400 "));

            response = _connector.getResponse("GET /context/forward/params?echo=badclient&bad=%88%A4 HTTP/1.0\n\n");
            assertThat(response, containsString(" 400 "));

            response = _connector.getResponse("GET /context/forward/badparams?echo=badclientandparam&bad=%88%A4 HTTP/1.0\n\n");
            assertThat(response, containsString(" 500 "));
        }
    }

    @Test
    public void testInclude() throws Exception
    {
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(AssertIncludeServlet.class, "/AssertIncludeServlet/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "INCLUDE";

        String responses = _connector.getResponse("GET /context/IncludeServlet?do=assertinclude&do=more&test=1 HTTP/1.0\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testForwardExForwardEx() throws Exception
    {
        _contextHandler.addServlet(RelativeDispatch2Servlet.class, "/RelDispatchServlet/*");
        _contextHandler.addServlet(ThrowServlet.class, "/include/throw/*");
        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 56\r\n" +
                "\r\n" +
                "THROWING\r\n" +
                "CAUGHT2 java.io.IOException: Expected\r\n" +
                "AFTER\r\n";

        String responses = _connector.getResponse("GET /context/RelDispatchServlet?path=include/throw HTTP/1.0\n\n");
        assertEquals(expected, responses);
    }

    @Test
    public void testIncludeExIncludeEx() throws Exception
    {
        _contextHandler.addServlet(RelativeDispatch2Servlet.class, "/RelDispatchServlet/*");
        _contextHandler.addServlet(ThrowServlet.class, "/include/throw/*");
        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 122\r\n" +
                "\r\n" +
                "BEFORE\r\n" +
                "THROWING\r\n" +
                "CAUGHT1 java.io.IOException: Expected\r\n" +
                "BETWEEN\r\n" +
                "THROWING\r\n" +
                "CAUGHT2 java.io.IOException: Expected\r\n" +
                "AFTER\r\n";

        String responses = _connector.getResponse("GET /context/RelDispatchServlet?include=true&path=include/throw HTTP/1.0\n\n");
        assertEquals(expected, responses);
    }

    @Test
    public void testForwardThenInclude() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(AssertForwardIncludeServlet.class, "/AssertForwardIncludeServlet/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "INCLUDE";

        String responses = _connector.getResponse("GET /context/ForwardServlet/forwardpath?do=include HTTP/1.0\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testIncludeThenForward() throws Exception
    {
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertIncludeForwardServlet.class, "/AssertIncludeForwardServlet/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "FORWARD";

        String responses = _connector.getResponse("GET /context/IncludeServlet/includepath?do=forward HTTP/1.0\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testServletForward() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "Roger That!";

        String responses = _connector.getResponse("GET /context/dispatch/test?forward=/roger/that HTTP/1.0\n" + "Host: localhost\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testServletForwardDotDot() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/that");

        String requests = "GET /context/dispatch/test?forward=/%2e%2e/roger/that HTTP/1.0\n" + "Host: localhost\n\n";

        String responses = _connector.getResponse(requests);

        assertThat(responses, startsWith("HTTP/1.1 404 "));
    }

    @Test
    public void testServletForwardEncodedDotDot() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/that");

        String requests = "GET /context/dispatch/test?forward=/%252e%252e/roger/that HTTP/1.0\n" + "Host: localhost\n\n";

        String responses = _connector.getResponse(requests);

        assertThat(responses, startsWith("HTTP/1.1 404 "));
    }

    @Test
    public void testServletInclude() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/*");

        String expected =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "Roger That!";

        String responses = _connector.getResponse("GET /context/dispatch/test?include=/roger/that HTTP/1.0\n" + "Host: localhost\n\n");

        assertEquals(expected, responses);
    }

    @Test
    public void testWorkingResourceHandler() throws Exception
    {
        String responses = _connector.getResponse("GET /resource/content.txt HTTP/1.0\n" + "Host: localhost\n\n");

        assertThat(responses, containsString("content goes here")); // from inside the context.txt file
    }

    @Test
    public void testIncludeToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String responses = _connector.getResponse("GET /context/resourceServlet/content.txt?do=include HTTP/1.0\n" + "Host: localhost\n\n");

        // from inside the context.txt file
        assertNotNull(responses);

        assertThat(responses, containsString("content goes here"));
    }

    @Test
    public void testForwardToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String responses = _connector.getResponse("GET /context/resourceServlet/content.txt?do=forward HTTP/1.0\n" + "Host: localhost\n\n");

        // from inside the context.txt file
        assertThat(responses, containsString("content goes here"));
    }

    @Test
    public void testWrappedIncludeToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String responses = _connector.getResponse("GET /context/resourceServlet/content.txt?do=include&wrapped=true HTTP/1.0\n" + "Host: localhost\n\n");

        // from inside the context.txt file
        assertThat(responses, containsString("content goes here"));
    }

    @Test
    public void testWrappedForwardToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String responses = _connector.getResponse("GET /context/resourceServlet/content.txt?do=forward&wrapped=true HTTP/1.0\n" + "Host: localhost\n\n");

        // from inside the context.txt file
        assertThat(responses, containsString("content goes here"));
    }

    @Test
    public void testForwardFilterToRogerServlet() throws Exception
    {
        _contextHandler.addServlet(RogerThatServlet.class, "/*");
        _contextHandler.addServlet(ReserveEchoServlet.class, "/recho/*");
        _contextHandler.addServlet(EchoServlet.class, "/echo/*");
        _contextHandler.addFilter(ForwardFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        String rogerResponse = _connector.getResponse("GET /context/ HTTP/1.0\n" + "Host: localhost\n\n");

        String echoResponse = _connector.getResponse("GET /context/foo?echo=echoText HTTP/1.0\n" + "Host: localhost\n\n");

        String rechoResponse = _connector.getResponse("GET /context/?echo=echoText HTTP/1.0\n" + "Host: localhost\n\n");

        assertThat(rogerResponse, containsString("Roger That!"));
        assertThat(echoResponse, containsString("echoText"));
        assertThat(rechoResponse, containsString("txeTohce"));
    }

    @Test
    public void testWrappedForwardCloseIntercepted() throws Exception
    {
        // Add filter that wraps response, intercepts close and writes after doChain
        _contextHandler.addFilter(WrappingFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        testForward();
    }

    @Test
    public void testDispatchMapping() throws Exception
    {
        _contextHandler.addServlet(new ServletHolder("TestServlet", MappingServlet.class), "/TestServlet");
        _contextHandler.addServlet(new ServletHolder("DispatchServlet", AsyncDispatch2TestServlet.class), "/DispatchServlet");
        _contextHandler.addServlet(new ServletHolder("DispatchServlet2", AsyncDispatch2TestServlet.class), "/DispatchServlet2");

        // TODO Test TCK hack for https://github.com/eclipse-ee4j/jakartaee-tck/issues/585
        String response = _connector.getResponse("GET /context/DispatchServlet HTTP/1.0\n\n");
        assertThat(response, containsString("matchValue=DispatchServlet, pattern=/DispatchServlet, servletName=DispatchServlet, mappingMatch=EXACT"));

        // TODO Test how it should work after fix for https://github.com/eclipse-ee4j/jakartaee-tck/issues/585
        String response2 = _connector.getResponse("GET /context/DispatchServlet2 HTTP/1.0\n\n");
        assertThat(response2, containsString("matchValue=TestServlet, pattern=/TestServlet, servletName=TestServlet, mappingMatch=EXACT"));
    }

    public static class WrappingFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            ResponseWrapper wrapper = new ResponseWrapper((HttpServletResponse)response);
            chain.doFilter(request, wrapper);
            wrapper.sendResponse(response.getOutputStream());
        }

        @Override
        public void destroy()
        {
        }
    }

    public static class ResponseWrapper extends HttpServletResponseWrapper
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public ResponseWrapper(HttpServletResponse response)
        {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException
        {
            return new ServletOutputStream()
            {
                @Override
                public boolean isReady()
                {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void write(int b) throws IOException
                {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException
                {
                    buffer.write(b, off, len);
                }

                @Override
                public void close() throws IOException
                {
                    buffer.close();
                }
            };
        }

        public void sendResponse(OutputStream out) throws IOException
        {
            out.write(buffer.toByteArray());
            out.close();
        }
    }

    public static class ForwardServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;

            if (request.getParameter("do").equals("include"))
                dispatcher = getServletContext().getRequestDispatcher("/IncludeServlet/includepath?do=assertforwardinclude");
            else if (request.getParameter("do").equals("assertincludeforward"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertIncludeForwardServlet/assertpath?do=end");
            else if (request.getParameter("do").equals("assertforward"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertForwardServlet?do=end&do=the");
            else if (request.getParameter("do").equals("ctx.echo"))
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("uri"));
            else if (request.getParameter("do").equals("req.echo"))
                dispatcher = request.getRequestDispatcher(request.getParameter("uri"));
            dispatcher.forward(request, response);
        }
    }

    public static class AlwaysForwardServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if ("/params".equals(request.getPathInfo()))
                getServletContext().getRequestDispatcher("/echo?echo=forward").forward(request, response);
            else if ("/badparams".equals(request.getPathInfo()))
                getServletContext().getRequestDispatcher("/echo?echo=forward&fbad=%88%A4").forward(request, response);
            else
                getServletContext().getRequestDispatcher("/echo").forward(request, response);
        }
    }

    public static class NamedForwardServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            getServletContext().getNamedDispatcher(request.getParameter("name")).forward(request, response);
        }
    }

    public static class NamedIncludeServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            getServletContext().getNamedDispatcher(request.getParameter("name")).include(request, response);
        }
    }

    public static class ForwardNonUTF8Servlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;
            request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "cp1251");
            dispatcher = getServletContext().getRequestDispatcher("/AssertForwardServlet?do=end&else=%D0%B2%D1%8B%D0%B1%D1%80%D0%B0%D0%BD%D0%BE%3D%D0%A2%D0%B5%D0%BC%D0%BF%D0%B5%D1%80%D0%B0%D1%82%D1%83%D1%80%D0%B0");
            dispatcher.forward(request, response);
        }
    }

    /*
     * Forward filter works with roger, echo and reverse echo servlets to test various
     * forwarding bits using filters.
     *
     * when there is an echo parameter and the path info is / it forwards to the reverse echo
     * anything else in the pathInfo and it sends straight to the echo servlet...otherwise its
     * all roger servlet
     */
    public static class ForwardFilter implements Filter
    {
        ServletContext servletContext;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            servletContext = filterConfig.getServletContext().getContext("/context");
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {

            if (servletContext == null || !(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse))
            {
                chain.doFilter(request, response);
                return;
            }

            HttpServletRequest req = (HttpServletRequest)request;
            HttpServletResponse resp = (HttpServletResponse)response;

            if (req.getParameter("echo") != null && "/".equals(req.getPathInfo()))
            {
                RequestDispatcher dispatcher = servletContext.getRequestDispatcher("/recho");
                dispatcher.forward(request, response);
            }
            else if (req.getParameter("echo") != null)
            {
                RequestDispatcher dispatcher = servletContext.getRequestDispatcher("/echo");
                dispatcher.forward(request, response);
            }
            else
            {
                chain.doFilter(request, response);
                return;
            }
        }

        @Override
        public void destroy()
        {

        }
    }

    public static class DispatchServletServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;

            if (request.getParameter("include") != null)
            {
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("include"));
                dispatcher.include(new ServletRequestWrapper(request), new ServletResponseWrapper(response));
            }
            else if (request.getParameter("forward") != null)
            {
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("forward"));
                if (dispatcher != null)
                    dispatcher.forward(new ServletRequestWrapper(request), new ServletResponseWrapper(response));
                else
                    response.sendError(404);
            }
        }
    }

    public static class IncludeServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;

            if (request.getParameter("do").equals("forward"))
                dispatcher = getServletContext().getRequestDispatcher("/ForwardServlet/forwardpath?do=assertincludeforward");
            else if (request.getParameter("do").equals("assertforwardinclude"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertForwardIncludeServlet/assertpath?do=end");
            else if (request.getParameter("do").equals("assertinclude"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertIncludeServlet?do=end&do=the");
            dispatcher.include(request, response);
        }
    }

    public static class RelativeDispatch2Servlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;
            String path = request.getParameter("path");
            String include = request.getParameter("include");
            ServletOutputStream out = response.getOutputStream();
            try
            {
                out.println("BEFORE");
                if (Boolean.parseBoolean(include))
                    request.getRequestDispatcher(path).include(request, response);
                else
                    request.getRequestDispatcher(path).forward(request, response);
                out.println("AFTER1");
            }
            catch (Throwable t)
            {
                out.println("CAUGHT1 " + t);
            }

            try
            {
                out.println("BETWEEN");
                if (Boolean.parseBoolean(include))
                    request.getRequestDispatcher(path).include(request, response);
                else
                    request.getRequestDispatcher(path).forward(request, response);
                out.println("AFTER2");
            }
            catch (Throwable t)
            {
                out.println("CAUGHT2 " + t);
            }
            out.println("AFTER");
        }
    }

    public static class RogerThatServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            res.getWriter().print("Roger That!");
        }
    }

    public static class ThrowServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            res.getOutputStream().println("THROWING");
            throw new IOException("Expected");
        }
    }

    public static class EchoServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            String[] echoText = req.getParameterValues("echo");

            if (echoText == null || echoText.length == 0)
            {
                throw new ServletException("echo is a required parameter");
            }
            else if (echoText.length == 1)
            {
                res.getWriter().print(echoText[0]);
            }
            else
            {
                for (String text : echoText)
                {
                    res.getWriter().print(text);
                }
            }
        }
    }

    public static class ReserveEchoServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            String echoText = req.getParameter("echo");

            if (echoText == null)
            {
                throw new ServletException("echo is a required parameter");
            }
            else
            {
                res.getWriter().print(new StringBuffer(echoText).reverse().toString());
            }
        }
    }

    public static class DispatchToResourceServlet extends HttpServlet implements Servlet
    {
        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
        {
            ServletContext targetContext = getServletConfig().getServletContext().getContext("/resource");

            RequestDispatcher dispatcher = targetContext.getRequestDispatcher(req.getPathInfo());

            if ("true".equals(req.getParameter("wrapped")))
            {
                if (req.getParameter("do").equals("forward"))
                {
                    dispatcher.forward(new HttpServletRequestWrapper(req), new HttpServletResponseWrapper(res));
                }
                else if (req.getParameter("do").equals("include"))
                {
                    dispatcher.include(new HttpServletRequestWrapper(req), new HttpServletResponseWrapper(res));
                }
                else
                {
                    throw new ServletException("type of forward or include is required");
                }
            }
            else
            {
                if (req.getParameter("do").equals("forward"))
                {
                    dispatcher.forward(req, res);
                }
                else if (req.getParameter("do").equals("include"))
                {
                    dispatcher.include(req, res);
                }
                else
                {
                    throw new ServletException("type of forward or include is required");
                }
            }
        }
    }

    public static class EchoURIServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/plain");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().println(request.getContextPath());
            response.getOutputStream().println(request.getServletPath());
            response.getOutputStream().println(request.getPathInfo());
            response.getOutputStream().println(request.getRequestURI());
        }
    }

    public static class AssertForwardServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            assertEquals("/context/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
            assertEquals("/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals(null, request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=assertforward&do=more&test=1", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("ForwardServlet", fwdMapping.getMatchValue());

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH,
                Dispatcher.FORWARD_SERVLET_PATH, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals(null, request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=end&do=the", request.getQueryString());
            assertEquals("/context/AssertForwardServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/AssertForwardServlet", request.getServletPath());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().print(request.getDispatcherType().toString());
        }
    }

    public static class AssertNonUTF8ForwardServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            byte[] cp1251Bytes = TypeUtil.fromHexString("d2e5ecefe5f0e0f2f3f0e0");
            String expectedCP1251String = new String(cp1251Bytes, "cp1251");

            assertEquals("/context/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
            assertEquals("/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals(null, request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=assertforward&foreign=%d2%e5%ec%ef%e5%f0%e0%f2%f3%f0%e0&test=1", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("ForwardServlet", fwdMapping.getMatchValue());

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH,
                Dispatcher.FORWARD_SERVLET_PATH, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals(null, request.getPathInfo());
            assertEquals(null, request.getPathTranslated());

            MultiMap<String> query = new MultiMap<>();
            UrlEncoded.decodeTo(request.getQueryString(), query, UrlEncoded.ENCODING);
            assertThat(query.getString("do"), is("end"));

            // Russian for "selected=Temperature"
            MultiMap<String> q2 = new MultiMap<>();
            UrlEncoded.decodeTo(query.getString("else"), q2, UrlEncoded.ENCODING);
            String russian = UrlEncoded.encode(q2, UrlEncoded.ENCODING, false);
            assertThat(russian, is("%D0%B2%D1%8B%D0%B1%D1%80%D0%B0%D0%BD%D0%BE=%D0%A2%D0%B5%D0%BC%D0%BF%D0%B5%D1%80%D0%B0%D1%82%D1%83%D1%80%D0%B0"));
            assertThat(query.containsKey("test"), is(false));
            assertThat(query.containsKey("foreign"), is(false));

            String[] vals = request.getParameterValues("foreign");
            assertTrue(vals != null);
            assertEquals(1, vals.length);
            assertEquals(expectedCP1251String, vals[0]);

            assertEquals("/context/AssertForwardServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/AssertForwardServlet", request.getServletPath());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().print(request.getDispatcherType().toString());
        }
    }

    public static class AssertIncludeServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            assertEquals("/context/AssertIncludeServlet", request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH));
            assertEquals("/AssertIncludeServlet", request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertEquals("do=end&do=the", request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));
            HttpServletMapping incMapping = (HttpServletMapping)request.getAttribute(Dispatcher.INCLUDE_MAPPING);
            assertNotNull(incMapping);
            assertEquals("AssertIncludeServlet", incMapping.getMatchValue());

            List expectedAttributeNames = Arrays.asList(Dispatcher.INCLUDE_REQUEST_URI, Dispatcher.INCLUDE_CONTEXT_PATH,
                Dispatcher.INCLUDE_SERVLET_PATH, Dispatcher.INCLUDE_QUERY_STRING, Dispatcher.INCLUDE_MAPPING);
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals(null, request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=assertinclude&do=more&test=1", request.getQueryString());
            assertEquals("/context/IncludeServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/IncludeServlet", request.getServletPath());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().print(request.getDispatcherType().toString());
        }
    }

    public static class AssertForwardIncludeServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            // include doesn't hide forward
            assertEquals("/context/ForwardServlet/forwardpath", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
            assertEquals("/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals("/forwardpath", request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=include", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("ForwardServlet", fwdMapping.getMatchValue());

            assertEquals("/context/AssertForwardIncludeServlet/assertpath", request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH));
            assertEquals("/AssertForwardIncludeServlet", request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertEquals("/assertpath", request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertEquals("do=end", request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));
            HttpServletMapping incMapping = (HttpServletMapping)request.getAttribute(Dispatcher.INCLUDE_MAPPING);
            assertNotNull(incMapping);
            assertEquals("AssertForwardIncludeServlet", incMapping.getMatchValue());

            List expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, Dispatcher.FORWARD_SERVLET_PATH,
                Dispatcher.FORWARD_PATH_INFO, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING,
                Dispatcher.INCLUDE_REQUEST_URI, Dispatcher.INCLUDE_CONTEXT_PATH, Dispatcher.INCLUDE_SERVLET_PATH,
                Dispatcher.INCLUDE_PATH_INFO, Dispatcher.INCLUDE_QUERY_STRING, Dispatcher.INCLUDE_MAPPING);
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals("/includepath", request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=assertforwardinclude", request.getQueryString());
            assertEquals("/context/IncludeServlet/includepath", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/IncludeServlet", request.getServletPath());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().print(request.getDispatcherType().toString());
        }
    }

    public static class AssertIncludeForwardServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            // forward hides include
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH));
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));
            assertEquals(null, request.getAttribute(Dispatcher.INCLUDE_MAPPING));

            assertEquals("/context/IncludeServlet/includepath", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
            assertEquals("/IncludeServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals("/includepath", request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=forward", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("IncludeServlet", fwdMapping.getMatchValue());

            List expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, Dispatcher.FORWARD_SERVLET_PATH,
                Dispatcher.FORWARD_PATH_INFO, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING);
            List requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals("/assertpath", request.getPathInfo());
            assertEquals(null, request.getPathTranslated());
            assertEquals("do=end", request.getQueryString());
            assertEquals("/context/AssertIncludeForwardServlet/assertpath", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/AssertIncludeForwardServlet", request.getServletPath());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().print(request.getDispatcherType().toString());
        }
    }

    public static class MappingServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            HttpServletMapping mapping = req.getHttpServletMapping();
            if (mapping == null)
            {
                resp.getWriter().println("Get null HttpServletMapping");
            }
            else
            {
                StringBuilder sb = new StringBuilder();
                sb.append("matchValue=" + mapping.getMatchValue())
                    .append(", pattern=" + mapping.getPattern())
                    .append(", servletName=" + mapping.getServletName())
                    .append(", mappingMatch=" + mapping.getMappingMatch());
                resp.getWriter().println(sb.toString());
            }
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            this.doGet(req, resp);
        }
    }

    public static class AsyncDispatch2TestServlet extends HttpServlet
    {
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException
        {
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0);
            asyncContext.dispatch("/TestServlet");
        }
    }
}
