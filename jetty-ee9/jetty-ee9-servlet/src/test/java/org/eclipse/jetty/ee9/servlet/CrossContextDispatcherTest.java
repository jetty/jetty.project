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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.ee9.nested.Dispatcher;
import org.eclipse.jetty.ee9.nested.Request;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CrossContextDispatcherTest
{
    public static final String MULTIPART = "--AaB03x\r\n" +
        "content-disposition: form-data; name=\"field1\"\r\n" +
        "\r\n" +
        "Joe Blow\r\n" +
        "--AaB03x\r\n" +
        "content-disposition: form-data; name=\"stuff\"\r\n" +
        "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
        "\r\n" +
        "000000000000000000000000000000000000000000000000000\r\n" +
        "--AaB03x--\r\n";

    public static final String MULTIPART_HEADERS = "Host: whatever\r\n" +
        "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
        "Content-Length: " + MULTIPART.getBytes().length + "\r\n" +
        "Connection: close\r\n";

    public static final String GET_INCLUDE = "GET /context/dispatch/?include=/reader HTTP/1.1\r\n";
    public static final String MULTIPART_INCLUDE_REQUEST = GET_INCLUDE +
        MULTIPART_HEADERS +
        "\r\n" +
        MULTIPART;

    public static final String GET_FORWARD = "GET /context/dispatch/?forward=/reader HTTP/1.1\r\n";

    public static final String MULTIPART_FORWARD_REQUEST = GET_FORWARD +
        MULTIPART_HEADERS +
        "\r\n" +
        MULTIPART;

    private static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty("java.io.tmpdir"), -1, -1, 2);
    private static final String FOREIGN_SERVLET_PACKAGE = "javax.servlet."; //EE8EE9-TRANSLATE
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _contextHandler;
    private ServletContextHandler _targetServletContextHandler;
    private ServletContextHandler _rootContextHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);

        ContextHandlerCollection contextCollection = new ContextHandlerCollection();
        _contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _contextHandler.setContextPath("/context");
        _contextHandler.setBaseResourceAsPath(MavenPaths.findTestResourceDir("contextResources"));
        _contextHandler.setCrossContextDispatchSupported(true);
        contextCollection.addHandler(_contextHandler);

        _targetServletContextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        _targetServletContextHandler.setContextPath("/foreign");
        _targetServletContextHandler.setBaseResourceAsPath(MavenPaths.findTestResourceDir("dispatchResourceTest"));
        _targetServletContextHandler.setCrossContextDispatchSupported(true);
        contextCollection.addHandler(_targetServletContextHandler);

        _rootContextHandler = new ServletContextHandler();
        _rootContextHandler.setContextPath("/");
        _rootContextHandler.setBaseResource(ResourceFactory.root().newResource(MavenPaths.findTestResourceDir("docroot")));
        _rootContextHandler.setCrossContextDispatchSupported(true);
        contextCollection.addHandler(_rootContextHandler);

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(ResourceFactory.root().newResource(MavenPaths.findTestResourceDir("dispatchResourceTest")));
        ContextHandler resourceContextHandler = new ContextHandler("/resource");
        resourceContextHandler.setHandler(resourceHandler);
        resourceContextHandler.setCrossContextDispatchSupported(true);
        contextCollection.addHandler(resourceContextHandler);
        _server.setHandler(contextCollection);
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
    public void testForwardToRoot() throws Exception
    {
        _rootContextHandler.addServlet(VerifyForwardServlet.class, "/verify/*");
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String rawResponse = _connector.getResponse("""
            GET /context/dispatch/?forward=/verify&ctx=/ HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String content = response.getContent();
        String[] contentLines = content.split("\\n");

        //verify forward attributes
        assertThat(content, containsString("Verified!"));
        assertThat(content, containsString("jakarta.servlet.forward.context_path=/context"));
        assertThat(content, containsString("jakarta.servlet.forward.servlet_path=/dispatch"));
        assertThat(content, containsString("jakarta.servlet.forward.path_info=/"));

        String forwardMapping = extractLine(contentLines, "jakarta.servlet.forward.mapping=");
        assertNotNull(forwardMapping);
        assertThat(forwardMapping, containsString("CrossContextDispatchServlet"));
        assertThat(content, containsString("jakarta.servlet.forward.query_string=forward=/verify&ctx=/"));
        assertThat(content, containsString("jakarta.servlet.forward.request_uri=/context/dispatch/"));
        //verify request values
        assertThat(content, containsString("REQUEST_URL=http://localhost/"));
        assertThat(content, containsString("CONTEXT_PATH="));
        assertThat(content, containsString("SERVLET_PATH=/verify"));
        assertThat(content, containsString("PATH_INFO=/pinfo"));
        String mapping = extractLine(contentLines, "MAPPING=");
        assertNotNull(mapping);
        assertThat(mapping, containsString("VerifyForwardServlet"));
        String params = extractLine(contentLines, "PARAMS=");
        assertNotNull(params);
        params = params.substring(params.indexOf("=") + 1);
        params = params.substring(1, params.length() - 1); //dump leading, trailing [ ]
        assertThat(Arrays.asList(StringUtil.csvSplit(params)), containsInAnyOrder("a", "forward", "ctx"));
        assertThat(content, containsString("REQUEST_URI=/verify/pinfo"));
    }

    @Test
    public void testIncludeToRoot() throws Exception
    {
        _rootContextHandler.addServlet(VerifyIncludeServlet.class, "/verify/*");
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String rawResponse = _connector.getResponse("""
            GET /context/dispatch/?include=/verify&ctx=/ HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String content = response.getContent();
        String[] contentLines = content.split("\\n");

        //verify include attributes
        assertThat(content, containsString("Verified!"));
        assertThat(content, containsString("jakarta.servlet.include.context_path="));
        assertThat(content, containsString("jakarta.servlet.include.servlet_path=/verify"));
        assertThat(content, containsString("jakarta.servlet.include.path_info=/pinfo"));
        String includeMapping = extractLine(contentLines, "jakarta.servlet.include.mapping=");
        assertThat(includeMapping, containsString("VerifyIncludeServlet"));
        assertThat(content, containsString("jakarta.servlet.include.request_uri=/verify/pinfo"));
        //verify request values
        assertThat(content, containsString("CONTEXT_PATH=/context"));
        assertThat(content, containsString("SERVLET_PATH=/dispatch"));
        assertThat(content, containsString("PATH_INFO=/"));
        String mapping = extractLine(contentLines, "MAPPING=");
        assertThat(mapping, containsString("CrossContextDispatchServlet"));
        assertThat(content, containsString("QUERY_STRING=include=/verify"));
        assertThat(content, containsString("REQUEST_URI=/context/dispatch/"));
        String params = extractLine(contentLines, "PARAMS=");
        assertNotNull(params);
        params = params.substring(params.indexOf("=") + 1);
        params = params.substring(1, params.length() - 1); //dump leading, trailing [ ]
        assertThat(Arrays.asList(StringUtil.csvSplit(params)), containsInAnyOrder("a", "include", "ctx"));
    }

    @Test
    public void testSimpleCrossContextForward() throws Exception
    {
        _targetServletContextHandler.addServlet(VerifyForwardServlet.class, "/verify/*");
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String rawResponse = _connector.getResponse("""
            GET /context/dispatch/?forward=/verify HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String content = response.getContent();
        String[] contentLines = content.split("\\n");

        //verify forward attributes
        assertThat(content, containsString("Verified!"));
        assertThat(content, containsString("jakarta.servlet.forward.context_path=/context"));
        assertThat(content, containsString("jakarta.servlet.forward.servlet_path=/dispatch"));
        assertThat(content, containsString("jakarta.servlet.forward.path_info=/"));

        String forwardMapping = extractLine(contentLines, "jakarta.servlet.forward.mapping=");
        assertNotNull(forwardMapping);
        assertThat(forwardMapping, containsString("CrossContextDispatchServlet"));
        assertThat(content, containsString("jakarta.servlet.forward.query_string=forward=/verify"));
        assertThat(content, containsString("jakarta.servlet.forward.request_uri=/context/dispatch/"));
        //verify request values
        assertThat(content, containsString("REQUEST_URL=http://localhost/foreign/"));
        assertThat(content, containsString("CONTEXT_PATH=/foreign"));
        assertThat(content, containsString("SERVLET_PATH=/verify"));
        assertThat(content, containsString("PATH_INFO=/pinfo"));
        String mapping = extractLine(contentLines, "MAPPING=");
        assertNotNull(mapping);
        assertThat(mapping, containsString("VerifyForwardServlet"));
        //TODO query string
        String params = extractLine(contentLines, "PARAMS=");
        assertNotNull(params);
        params = params.substring(params.indexOf("=") + 1);
        params = params.substring(1, params.length() - 1); //dump leading, trailing [ ]
        assertThat(Arrays.asList(StringUtil.csvSplit(params)), containsInAnyOrder("a", "forward"));
        assertThat(content, containsString("REQUEST_URI=/foreign/verify/pinfo"));
        //check for object identity
        assertThat(content, containsString("TYPE=org.eclipse.jetty.ee9.servlet.CrossContextDispatcherTest$MyHttpServletRequestWrapper"));
    }

    @Test
    public void testEncodedCrossContextForward() throws Exception
    {
        _server.stop();
        _targetServletContextHandler.addServlet(VerifyForwardServlet.class, "/verify/*");
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");
        _server.getContainedBeans(HttpConnectionFactory.class).forEach(f -> f.getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT.with("test", UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING)));
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET /context/dispatch/?forward=/verify/%25%20test HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String content = response.getContent();
        String[] contentLines = content.split("\\n");

        //verify forward attributes
        assertThat(content, containsString("Verified!"));
        assertThat(content, containsString("jakarta.servlet.forward.context_path=/context"));
        assertThat(content, containsString("jakarta.servlet.forward.servlet_path=/dispatch"));
        assertThat(content, containsString("jakarta.servlet.forward.path_info=/"));

        String forwardMapping = extractLine(contentLines, "jakarta.servlet.forward.mapping=");
        assertNotNull(forwardMapping);
        assertThat(forwardMapping, containsString("CrossContextDispatchServlet"));
        assertThat(content, containsString("jakarta.servlet.forward.query_string=forward=/verify"));
        assertThat(content, containsString("jakarta.servlet.forward.request_uri=/context/dispatch/"));
        //verify request values
        assertThat(content, containsString("REQUEST_URL=http://localhost/foreign/"));
        assertThat(content, containsString("CONTEXT_PATH=/foreign"));
        assertThat(content, containsString("SERVLET_PATH=/verify"));
        assertThat(content, containsString("PATH_INFO=/% test/pinfo"));
        String mapping = extractLine(contentLines, "MAPPING=");
        assertNotNull(mapping);
        assertThat(mapping, containsString("VerifyForwardServlet"));
        String params = extractLine(contentLines, "PARAMS=");
        assertNotNull(params);
        params = params.substring(params.indexOf("=") + 1);
        params = params.substring(1, params.length() - 1); //dump leading, trailing [ ]
        assertThat(Arrays.asList(StringUtil.csvSplit(params)), containsInAnyOrder("a", "forward"));
        assertThat(content, containsString("REQUEST_URI=/foreign/verify/%25%20test/pinfo"));
    }

    @Test
    public void testSimpleCrossContextInclude() throws Exception
    {
        _targetServletContextHandler.addServlet(VerifyIncludeServlet.class, "/verify/*");
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

         String rawResponse = _connector.getResponse("""
            GET /context/dispatch/?include=/verify HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        String content = response.getContent();
        String[] contentLines = content.split("\\n");

        //verify include attributes
        assertThat(content, containsString("Verified!"));
        assertThat(content, containsString("jakarta.servlet.include.context_path=/foreign"));
        assertThat(content, containsString("jakarta.servlet.include.servlet_path=/verify"));
        assertThat(content, containsString("jakarta.servlet.include.path_info=/pinfo"));
        String includeMapping = extractLine(contentLines, "jakarta.servlet.include.mapping=");
        assertThat(includeMapping, containsString("VerifyIncludeServlet"));
        //TODO query string
        assertThat(content, containsString("jakarta.servlet.include.request_uri=/foreign/verify/pinfo"));
        //verify request values
        assertThat(content, containsString("CONTEXT_PATH=/context"));
        assertThat(content, containsString("SERVLET_PATH=/dispatch"));
        assertThat(content, containsString("PATH_INFO=/"));
        String mapping = extractLine(contentLines, "MAPPING=");
        assertThat(mapping, containsString("CrossContextDispatchServlet"));
        assertThat(content, containsString("QUERY_STRING=include=/verify"));
        assertThat(content, containsString("REQUEST_URI=/context/dispatch/"));
        //check for object identity
        assertThat(content, containsString("TYPE=org.eclipse.jetty.ee9.servlet.CrossContextDispatcherTest$MyHttpServletRequestWrapper"));
        String params = extractLine(contentLines, "PARAMS=");
        assertNotNull(params);
        params = params.substring(params.indexOf("=") + 1);
        params = params.substring(1, params.length() - 1); //dump leading, trailing [ ]
        assertThat(Arrays.asList(StringUtil.csvSplit(params)), containsInAnyOrder("a", "include"));
    }

    @Test
    public void testMultiPartBeforeCrossContextInclude() throws Exception
    {
        _targetServletContextHandler.addServlet(MultiPartReadingServlet.class, "/reader/*");
        _contextHandler.addFilter(MultiPartReadingFilter.class, "/dispatch/*", EnumSet.of(DispatcherType.REQUEST));
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String rawResponse = _connector.getResponse(MULTIPART_INCLUDE_REQUEST);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String content = response.getContent();
        assertThat(content, containsString("field1=yes"));
        assertThat(content, containsString("stuff=yes"));
    }

    @Test
    public void testMultiPartAfterCrossContextInclude() throws Exception
    {
        _targetServletContextHandler.addServlet(MultiPartReadingServlet.class, "/reader/*");
        CountDownLatch latch = new CountDownLatch(2);
        Servlet dispatcher = new CrossContextDispatchServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                super.doGet(request, response);

                Part field1 = request.getPart("field1");
                if (field1 != null)
                    latch.countDown();
                Part stuff = request.getPart("stuff");
                if (stuff != null)
                    latch.countDown();
            }
        };
        _contextHandler.addServlet(new ServletHolder(dispatcher), "/dispatch/*");

        String rawResponse = _connector.getResponse(MULTIPART_INCLUDE_REQUEST);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMultiPartBeforeCrossContextForward() throws Exception
    {
        _targetServletContextHandler.addServlet(MultiPartReadingServlet.class, "/reader/*");
        _contextHandler.addFilter(MultiPartReadingFilter.class, "/dispatch/*", EnumSet.of(DispatcherType.REQUEST));
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String rawResponse = _connector.getResponse(MULTIPART_FORWARD_REQUEST);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String content = response.getContent();
        assertThat(content, containsString("field1=yes"));
        assertThat(content, containsString("stuff=yes"));
    }

    @Test
    public void testMultiPartAfterCrossContextForward() throws Exception
    {
        _targetServletContextHandler.addServlet(MultiPartReadingServlet.class, "/reader/*");
        CountDownLatch latch = new CountDownLatch(2);
        Servlet dispatcher = new CrossContextDispatchServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                super.doGet(request, response);

                Part field1 = request.getPart("field1");
                if (field1 != null)
                    latch.countDown();
                Part stuff = request.getPart("stuff");
                if (stuff != null)
                    latch.countDown();
            }
        };
        _contextHandler.addServlet(new ServletHolder(dispatcher), "/dispatch/*");

        String rawResponse = _connector.getResponse(MULTIPART_FORWARD_REQUEST);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCrossContextForwardAndFilter() throws Exception
    {
        FilterHolder filterHolder = new FilterHolder(
            (request, response, chain) ->
            {
                // verify that expected RequestURL is still sane during Filter.
                HttpServletRequest httpRequest = (HttpServletRequest)request;
                HttpServletResponse httpResponse = (HttpServletResponse)response;
                StringBuffer requestUrl = httpRequest.getRequestURL();
                httpResponse.addHeader("X-Filter-RequestURL", requestUrl.toString());
                chain.doFilter(httpRequest, httpResponse);
            }
        );
        _targetServletContextHandler.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.FORWARD));
        _targetServletContextHandler.addServlet(VerifyForwardServlet.class, "/verify/*");
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String rawRequest = """
                GET /context/dispatch/?forward=/verify HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """;

        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        String expectedRequestURL = "http://localhost/foreign/verify/pinfo";
        assertThat(response.get("X-Filter-RequestURL"), is(expectedRequestURL));

        String content = response.getContent();
        List<String> contentLines = List.of(content.split("\\n"));
        assertThat(contentLines, hasItem("REQUEST_URL=" + expectedRequestURL));
    }

    @Test
    public void testParamsBeforeCrossContextForward() throws Exception
    {
        _targetServletContextHandler.addServlet(ParameterReadingServlet.class, "/reader/*");
        _contextHandler.addFilter(ParameterReadingFilter.class, "/dispatch/*", EnumSet.of(DispatcherType.REQUEST));
        _contextHandler.addServlet(CrossContextDispatchServlet.class, "/dispatch/*");

        String form = "a=xxx";
        String rawResponse = _connector.getResponse(
            "POST /context/dispatch/?forward=/reader HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: " + form.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
             form);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("a="));
    }

    @Test
    public void testParamsAfterCrossContextForward() throws Exception
    {
        _targetServletContextHandler.addServlet(ParameterReadingServlet.class, "/reader/*");
        CountDownLatch latch = new CountDownLatch(2);
        Servlet dispatcher = new CrossContextDispatchServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                super.doGet(request, response);

                if (!StringUtil.isBlank(request.getParameter("param")))
                    latch.countDown();
                if (!StringUtil.isBlank(request.getParameter("a")))
                    latch.countDown();
            }
        };

        _contextHandler.addServlet(new ServletHolder(dispatcher), "/dispatch/*");

        String form = "a=xxx";
        String rawResponse = _connector.getResponse(
            "POST /context/dispatch/?forward=/reader&param=a HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: " + form.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
             form);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testForwardToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/resourceServlet/content.txt?do=forward HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // from inside the context.txt file
        assertThat(response.getContent(), containsString("content goes here"));
    }
    
    @Test
    public void testWrappedIncludeToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/resourceServlet/content.txt?do=include&wrapped=true HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // from inside the context.txt file
        assertThat(response.getContent(), containsString("content goes here"));
    }

    @Test
    public void testWrappedForwardToResourceHandler() throws Exception
    {
        _contextHandler.addServlet(DispatchToResourceServlet.class, "/resourceServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/resourceServlet/content.txt?do=forward&wrapped=true HTTP/1.1
            Host: localhost\r
            Connection: close\r
            \r
            """);

        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // from inside the context.txt file
        assertThat(response.getContent(), containsString("content goes here"));
    }

    public static class WrappingFilter implements Filter
    {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            ResponseWrapper wrapper = new ResponseWrapper((HttpServletResponse)response);
            chain.doFilter(request, wrapper);
            wrapper.sendResponse(response.getOutputStream());
        }
    }

    public static class MyHttpServletRequestWrapper extends HttpServletRequestWrapper
    {
        public MyHttpServletRequestWrapper(HttpServletRequest request)
        {
            super(request);
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
                public void write(int b)
                {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len)
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
            else if (request.getParameter("do").equals("always"))
                dispatcher = request.getRequestDispatcher("/AlwaysForwardServlet");
            assert dispatcher != null;
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
            RequestDispatcher dispatcher;
            request.setAttribute("org.eclipse.jetty.server.Request.queryEncoding", "cp1251");
            dispatcher = getServletContext().getRequestDispatcher("/AssertForwardServlet?do=end&else=%D0%B2%D1%8B%D0%B1%D1%80%D0%B0%D0%BD%D0%BE%3D%D0%A2%D0%B5%D0%BC%D0%BF%D0%B5%D1%80%D0%B0%D1%82%D1%83%D1%80%D0%B0");
            dispatcher.forward(request, response);
        }
    }

    public static class MultiPartReadingFilter implements Filter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            //cause the MULTIPART to be parsed on the request
            HttpServletRequest httpServletRequest = (HttpServletRequest)request;
            httpServletRequest.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTIPART_CONFIG_ELEMENT);
            Collection<Part> parts = httpServletRequest.getParts();
            assertThat(parts, notNullValue());

            chain.doFilter(request, response);
        }
    }

    public static class MultiPartReadingServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            HttpServletRequest httpServletRequest = (HttpServletRequest)req;
            if (httpServletRequest.getAttribute(Request.MULTIPART_CONFIG_ELEMENT) == null)
                httpServletRequest.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, MULTIPART_CONFIG_ELEMENT);
            Collection<Part> parts = httpServletRequest.getParts();
            assertThat(parts, notNullValue());
            Part field1 = httpServletRequest.getPart("field1");
            res.getWriter().println("field1=" + (field1 == null ? "no" : "yes"));
            Part stuff = httpServletRequest.getPart("stuff");
            res.getWriter().println("stuff=" + (stuff == null ? "no" : "yes"));
        }
    }

    public static class ParameterReadingFilter implements Filter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            //cause the params to be parsed on the request
            Map<String, String[]> params = request.getParameterMap();
            assertThat(params, notNullValue());

            chain.doFilter(request, response);
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
            servletContext = filterConfig.getServletContext();
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {

            if (servletContext == null || !(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse))
            {
                chain.doFilter(request, response);
                return;
            }

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
            }
        }
    }

    public static class DispatchServletServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher;

            if (request.getParameter("include") != null)
            {
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("include"));
                dispatcher.include(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
            }
            else if (request.getParameter("forward") != null)
            {
                dispatcher = getServletContext().getRequestDispatcher(request.getParameter("forward"));
                if (dispatcher != null)
                    dispatcher.forward(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
                else
                    response.sendError(404);
            }
        }
    }

    public static class CrossContextDispatchServlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            doGet(req, resp);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher;
            String ctx = request.getParameter("ctx");
            if (StringUtil.isBlank(ctx))
                ctx = "/foreign";
            if (request.getParameter("forward") != null)
            {
                ServletContext foreign = getServletContext().getContext(ctx);
                assertNotNull(foreign);
                dispatcher = foreign.getRequestDispatcher(URIUtil.encodePath(request.getParameter("forward")) + "/pinfo?a=b");

                if (dispatcher == null)
                       response.sendError(404, "No dispatcher for forward");
                else
                    dispatcher.forward(new MyHttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));

                assertThat(Collections.list(request.getAttributeNames()), not(containsInAnyOrder(RequestDispatcher.FORWARD_PATH_INFO,
                    RequestDispatcher.FORWARD_CONTEXT_PATH, RequestDispatcher.FORWARD_MAPPING,
                    RequestDispatcher.FORWARD_QUERY_STRING, RequestDispatcher.FORWARD_REQUEST_URI,
                    RequestDispatcher.FORWARD_SERVLET_PATH)));
            }
            else if (request.getParameter("include") != null)
            {
                ServletContext foreign = getServletContext().getContext(ctx);
                assertNotNull(foreign);
                dispatcher = foreign.getRequestDispatcher(request.getParameter("include") + "/pinfo?a=b");

                if (dispatcher == null)
                    response.sendError(404, "No dispatcher for include");
                else
                    dispatcher.include(new MyHttpServletRequestWrapper(request), response);

                assertThat(Collections.list(request.getAttributeNames()), not(containsInAnyOrder(RequestDispatcher.INCLUDE_CONTEXT_PATH,
                    RequestDispatcher.INCLUDE_MAPPING, RequestDispatcher.INCLUDE_PATH_INFO,
                    RequestDispatcher.INCLUDE_QUERY_STRING, RequestDispatcher.INCLUDE_REQUEST_URI,
                    RequestDispatcher.INCLUDE_SERVLET_PATH)));
            }
            else
                response.sendError(404, "No action");
        }
    }

    public static class IncludeServlet extends HttpServlet implements Servlet
    {
        // The logic linked to this field be deleted and the writer always used once #10155 is fixed.
        private final boolean useWriter;

        public IncludeServlet()
        {
            this(false);
        }

        public IncludeServlet(boolean useWriter)
        {
            this.useWriter = useWriter;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            RequestDispatcher dispatcher = null;
            boolean headers = Boolean.parseBoolean(request.getParameter("headers"));

            if (useWriter)
                response.getWriter().println("Include:");
            else
                response.getOutputStream().write("Include:\n".getBytes(StandardCharsets.US_ASCII));

            if (request.getParameter("do").equals("forward"))
                dispatcher = getServletContext().getRequestDispatcher("/ForwardServlet/forwardpath?do=assertincludeforward");
            else if (request.getParameter("do").equals("assertforwardinclude"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertForwardIncludeServlet/assertpath?do=end");
            else if (request.getParameter("do").equals("assertinclude"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertIncludeServlet?do=end&do=the&headers=" + headers);
            else if (request.getParameter("do").equals("static"))
                dispatcher = getServletContext().getRequestDispatcher("/test.txt");
            else if (request.getParameter("do").equals("hello"))
                dispatcher = getServletContext().getRequestDispatcher("/Hello");

            assert dispatcher != null;

            dispatcher.include(request, response);

            if (useWriter)
                response.getWriter().println("---");
            else
                response.getOutputStream().write("---\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static class HelloServlet extends HttpServlet implements Servlet
    {
        private final boolean useWriter;

        public HelloServlet(boolean useWriter)
        {
            this.useWriter = useWriter;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (useWriter)
                response.getWriter().println("Hello");
            else
                response.getOutputStream().write("Hello\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static class RelativeDispatch2Servlet extends HttpServlet implements Servlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
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

    public static class ParameterReadingServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            Map<String, String[]> params = req.getParameterMap();

            for (String key : params.keySet())
            {
                res.getWriter().print(key + "=");
                String[] val = params.get(key);
                if (val == null)
                    res.getWriter().println();
                else if (val.length == 1)
                    res.getWriter().println(val[0]);
                else
                {
                    res.getWriter().println(Arrays.asList(val));
                }
            }
        }
    }

    public static class VerifyForwardServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            if (DispatcherType.FORWARD.equals(req.getDispatcherType()))
            {
                res.getWriter().println("Verified!");
                res.getWriter().println("----------- FORWARD ATTRIBUTES");
                res.getWriter().println(RequestDispatcher.FORWARD_CONTEXT_PATH + "=" + req.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH));
                res.getWriter().println(RequestDispatcher.FORWARD_SERVLET_PATH + "=" + req.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
                res.getWriter().println(RequestDispatcher.FORWARD_PATH_INFO + "=" + req.getAttribute(RequestDispatcher.FORWARD_PATH_INFO));
                res.getWriter().println(RequestDispatcher.FORWARD_MAPPING + "=" + req.getAttribute(RequestDispatcher.FORWARD_MAPPING));
                res.getWriter().println(RequestDispatcher.FORWARD_QUERY_STRING + "=" + req.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
                res.getWriter().println(RequestDispatcher.FORWARD_REQUEST_URI + "=" + req.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI));
                res.getWriter().println("----------- REQUEST");
                HttpServletRequest httpServletRequest = (HttpServletRequest)req;
                res.getWriter().println("REQUEST_URL=" + httpServletRequest.getRequestURL());
                res.getWriter().println("CONTEXT_PATH=" + httpServletRequest.getServletContext().getContextPath());
                res.getWriter().println("SERVLET_PATH=" + httpServletRequest.getServletPath());
                res.getWriter().println("PATH_INFO=" + httpServletRequest.getPathInfo());
                res.getWriter().println("MAPPING=" + httpServletRequest.getHttpServletMapping());
                res.getWriter().println("QUERY_STRING=" + httpServletRequest.getQueryString());
                res.getWriter().println("REQUEST_URI=" + httpServletRequest.getRequestURI());
                Enumeration<String> names = httpServletRequest.getParameterNames();
                res.getWriter().println("PARAMS=" + Collections.list(names));
                res.getWriter().println("TYPE=" + httpServletRequest.getClass().getName());
            }
        }
    }

    public static class VerifyIncludeServlet extends GenericServlet
    {
         @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            if (DispatcherType.INCLUDE.equals(req.getDispatcherType()))
            {
                PrintWriter writer = res.getWriter();
                writer.println("Verified!");
                writer.println("----------- INCLUDE ATTRIBUTES");
                printAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, req, writer);
                printAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, req, writer);
                printAttribute(RequestDispatcher.INCLUDE_PATH_INFO, req, writer);
                printAttribute(RequestDispatcher.INCLUDE_MAPPING, req, writer);
                printAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, req, writer);
                printAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, req, writer);
                writer.println("----------- REQUEST");
                HttpServletRequest httpServletRequest = (HttpServletRequest)req;
                writer.println("CONTEXT_PATH=" + httpServletRequest.getContextPath());
                writer.println("SERVLET_PATH=" + httpServletRequest.getServletPath());
                writer.println("PATH_INFO=" + httpServletRequest.getPathInfo());
                writer.println("MAPPING=" + httpServletRequest.getHttpServletMapping());
                writer.println("QUERY_STRING=" + httpServletRequest.getQueryString());
                writer.println("REQUEST_URI=" + httpServletRequest.getRequestURI());
                writer.println("TYPE=" + httpServletRequest.getClass().getName());
                Enumeration<String> names = httpServletRequest.getParameterNames();
                writer.println("PARAMS=" + Collections.list(names));
            }
        }

        protected void printAttribute(String attributeName, ServletRequest request, PrintWriter writer)
        {
            writer.println(attributeName + "=" + request.getAttribute(attributeName));
        }
    }

    public static class VerifySimulatedCrossEnvironmentIncludeServlet extends VerifyIncludeServlet
    {
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            if (DispatcherType.INCLUDE.equals(req.getDispatcherType()))
            {
                PrintWriter writer = res.getWriter();
                writer.println("Verified!");
                writer.println("----------- INCLUDE ATTRIBUTES");
                printAttribute(FOREIGN_SERVLET_PACKAGE + "include.context_path", req, writer);
                printAttribute(FOREIGN_SERVLET_PACKAGE + "include.servlet_path", req, writer);
                printAttribute(FOREIGN_SERVLET_PACKAGE + "include.path_info", req, writer);
                printAttribute(FOREIGN_SERVLET_PACKAGE + "include.mapping", req, writer);
                printAttribute(FOREIGN_SERVLET_PACKAGE + "include.query_string", req, writer);
                printAttribute(FOREIGN_SERVLET_PACKAGE + "include.request_uri", req, writer);
                writer.println("----------- REQUEST");
                HttpServletRequest httpServletRequest = (HttpServletRequest)req;
                writer.println("CONTEXT_PATH=" + httpServletRequest.getContextPath());
                writer.println("SERVLET_PATH=" + httpServletRequest.getServletPath());
                writer.println("PATH_INFO=" + httpServletRequest.getPathInfo());
                writer.println("MAPPING=" + httpServletRequest.getHttpServletMapping());
                writer.println("QUERY_STRING=" + httpServletRequest.getQueryString());
                writer.println("REQUEST_URI=" + httpServletRequest.getRequestURI());
                Enumeration<String> names = httpServletRequest.getParameterNames();
                writer.println("PARAMS=" + Collections.list(names));
            }
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

    public static class SendErrorServlet extends HttpServlet
    {
        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
        {
            res.sendError(590, "Five Nine Zero");
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
                res.getWriter().print(new StringBuffer(echoText).reverse());
            }
        }
    }

    public static class DispatchToResourceServlet extends HttpServlet implements Servlet
    {
        @Override
        public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException
        {
            // TODO: the `/resource` is a jetty-core ContextHandler, and is not a ServletContextHandler so it cannot return a ServletContext.

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

    public static class IncludeEchoURIServlet extends HttpServlet implements Servlet
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
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH));
            HttpServletMapping mapping = (HttpServletMapping)request.getAttribute(RequestDispatcher.INCLUDE_MAPPING);
            response.getOutputStream().println(mapping == null ? null : mapping.getMatchValue());
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO));
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING));
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI));
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH));
        }
    }

    public static class ForwardEchoURIServlet extends HttpServlet implements Servlet
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
            HttpServletMapping mapping = request.getHttpServletMapping();
            response.getOutputStream().println(mapping == null ? null : mapping.getServletName());
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH));
            HttpServletMapping attrMapping = (HttpServletMapping)request.getAttribute(RequestDispatcher.FORWARD_MAPPING);
            response.getOutputStream().println(attrMapping == null ? null : attrMapping.getMatchValue());
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.FORWARD_PATH_INFO));
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING));
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI));
            response.getOutputStream().println((String)request.getAttribute(RequestDispatcher.FORWARD_SERVLET_PATH));
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
            assertNull(request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=assertforward&do=more&test=1", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("ForwardServlet", fwdMapping.getMatchValue());

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH,
                Dispatcher.FORWARD_SERVLET_PATH, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertNull(request.getPathInfo());
            assertNull(request.getPathTranslated());
            assertEquals("do=end&do=the", request.getQueryString());
            assertEquals("/context/AssertForwardServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/AssertForwardServlet", request.getServletPath());
            assertEquals("http://local:80/context/AssertForwardServlet", request.getRequestURL().toString());

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
            byte[] cp1251Bytes = StringUtil.fromHexString("d2e5ecefe5f0e0f2f3f0e0");
            String expectedCP1251String = new String(cp1251Bytes, "cp1251");

            assertEquals("/context/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
            assertEquals("/ForwardServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertNull(request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=assertforward&foreign=%d2%e5%ec%ef%e5%f0%e0%f2%f3%f0%e0&test=1", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("ForwardServlet", fwdMapping.getMatchValue());

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH,
                Dispatcher.FORWARD_SERVLET_PATH, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertNull(request.getPathInfo());
            assertNull(request.getPathTranslated());

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
            assertNotNull(vals);
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
            assertNull(request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertThat((String)request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING), containsString("do=end&do=the"));
            HttpServletMapping incMapping = (HttpServletMapping)request.getAttribute(Dispatcher.INCLUDE_MAPPING);
            assertNotNull(incMapping);
            assertEquals("AssertIncludeServlet", incMapping.getMatchValue());

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.INCLUDE_REQUEST_URI, Dispatcher.INCLUDE_CONTEXT_PATH,
                Dispatcher.INCLUDE_SERVLET_PATH, Dispatcher.INCLUDE_QUERY_STRING, Dispatcher.INCLUDE_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertNull(request.getPathInfo());
            assertNull(request.getPathTranslated());
            assertThat(request.getQueryString(), containsString("do=assertinclude&do=more&test=1"));
            assertEquals("/context/IncludeServlet", request.getRequestURI());
            assertEquals("/context", request.getContextPath());
            assertEquals("/IncludeServlet", request.getServletPath());

            response.setContentType("text/html");
            if (Boolean.parseBoolean(request.getParameter("headers")))
            {
                response.setHeader("org.eclipse.jetty.server.include.specialSetHeader", "specialSetHeader");
                response.setHeader("org.eclipse.jetty.server.include.specialAddHeader", "specialAddHeader");
            }
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

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, Dispatcher.FORWARD_SERVLET_PATH,
                Dispatcher.FORWARD_PATH_INFO, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING,
                Dispatcher.INCLUDE_REQUEST_URI, Dispatcher.INCLUDE_CONTEXT_PATH, Dispatcher.INCLUDE_SERVLET_PATH,
                Dispatcher.INCLUDE_PATH_INFO, Dispatcher.INCLUDE_QUERY_STRING, Dispatcher.INCLUDE_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals("/includepath", request.getPathInfo());
            assertNull(request.getPathTranslated());
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
            assertNull(request.getAttribute(Dispatcher.INCLUDE_REQUEST_URI));
            assertNull(request.getAttribute(Dispatcher.INCLUDE_CONTEXT_PATH));
            assertNull(request.getAttribute(Dispatcher.INCLUDE_SERVLET_PATH));
            assertNull(request.getAttribute(Dispatcher.INCLUDE_PATH_INFO));
            assertNull(request.getAttribute(Dispatcher.INCLUDE_QUERY_STRING));
            assertNull(request.getAttribute(Dispatcher.INCLUDE_MAPPING));

            assertEquals("/context/IncludeServlet/includepath", request.getAttribute(Dispatcher.FORWARD_REQUEST_URI));
            assertEquals("/context", request.getAttribute(Dispatcher.FORWARD_CONTEXT_PATH));
            assertEquals("/IncludeServlet", request.getAttribute(Dispatcher.FORWARD_SERVLET_PATH));
            assertEquals("/includepath", request.getAttribute(Dispatcher.FORWARD_PATH_INFO));
            assertEquals("do=forward", request.getAttribute(Dispatcher.FORWARD_QUERY_STRING));
            HttpServletMapping fwdMapping = (HttpServletMapping)request.getAttribute(Dispatcher.FORWARD_MAPPING);
            assertNotNull(fwdMapping);
            assertEquals("IncludeServlet", fwdMapping.getMatchValue());

            List<String> expectedAttributeNames = Arrays.asList(Dispatcher.FORWARD_REQUEST_URI, Dispatcher.FORWARD_CONTEXT_PATH, Dispatcher.FORWARD_SERVLET_PATH,
                Dispatcher.FORWARD_PATH_INFO, Dispatcher.FORWARD_QUERY_STRING, Dispatcher.FORWARD_MAPPING);
            List<String> requestAttributeNames = Collections.list(request.getAttributeNames());
            assertTrue(requestAttributeNames.containsAll(expectedAttributeNames));

            assertEquals("/assertpath", request.getPathInfo());
            assertNull(request.getPathTranslated());
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
                String sb = "matchValue=" + mapping.getMatchValue() +
                    ", pattern=" + mapping.getPattern() +
                    ", servletName=" + mapping.getServletName() +
                    ", mappingMatch=" + mapping.getMappingMatch();
                resp.getWriter().println(sb);
            }
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            this.doGet(req, resp);
        }
    }

    public static class AsyncDispatchTestServlet extends HttpServlet
    {
        public void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException
        {
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(0);
            String target = req.getParameter("target");
            target = StringUtil.isBlank(target) ? "/TestServlet" : target;
            asyncContext.dispatch(target);
        }
    }

    public String extractLine(String[] lines, String startsWith)
    {
        if (lines == null)
            return null;
        if (StringUtil.isBlank(startsWith))
            return null;

        String line = null;

        for (String s : lines)
        {
            if (s.startsWith(startsWith))
            {
                return s;
            }
        }
        return null;
    }
}
