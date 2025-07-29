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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DispatcherTest
{
    private static final Logger LOG = LoggerFactory.getLogger(DispatcherTest.class);

    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _contextHandler;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);

        _contextHandler = new ServletContextHandler();
        _contextHandler.setContextPath("/context");
        _contextHandler.setBaseResourceAsPath(MavenTestingUtils.getTestResourcePathDir("contextResources"));
        _server.setHandler(_contextHandler);
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
    public void testForwardToWelcome() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(DefaultServlet.class, "/");
        _server.start();

        String responses = _connector.getResponse("""
            GET /context/ForwardServlet?do=req.echo&uri=/subdir HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        assertThat(responses, containsString("HTTP/1.1 302 Found"));
    }

    @Test
    public void testForward() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertForwardServlet.class, "/AssertForwardServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/ForwardServlet?do=assertforward&do=more&test=1 HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Type: text/html\r
            Content-Length: 7\r
            Connection: close\r
            \r
            FORWARD""";

        assertEquals(expected, rawResponse);
    }
    
    @Test
    public void testMultiPartForwardAttribute() throws Exception
    {
        ServletHolder forwardServlet = new ServletHolder(new ForwardServlet());
        forwardServlet.getRegistration().setMultipartConfig(new MultipartConfigElement("/tmp"));
        _contextHandler.addServlet(forwardServlet, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertMultiPartForwardServlet.class, "/AssertMultiPartForwardServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/ForwardServlet?do=assertmultipart&do=more&test=1 HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Type: text/html\r
            Content-Length: 42\r
            Connection: close\r
            \r
            org.eclipse.jetty.multipartConfig = null\r
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testForwardThenForward() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AlwaysForwardServlet.class, "/AlwaysForwardServlet/*");
        ServletHolder holder = _contextHandler.getServletHandler().newServletHolder(Source.EMBEDDED);
        holder.setHeldClass(ForwardEchoURIServlet.class);
        holder.setName("ForwardEchoURIServlet"); //use easy-to-test name
        _contextHandler.addServlet(holder, "/echo/*");


        String rawResponse = _connector.getResponse("""
            GET /context/ForwardServlet?do=always HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Type: text/plain\r
            Content-Length: 146\r
            Connection: close\r
            \r
            /context\r
            /echo\r
            null\r
            /context/echo\r
            ForwardEchoURIServlet\r
            /context\r
            ForwardServlet\r
            null\r
            do=always\r
            /context/ForwardServlet\r
            /ForwardServlet\r
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testForwardNonUTF8() throws Exception
    {
        _contextHandler.addServlet(ForwardNonUTF8Servlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertNonUTF8ForwardServlet.class, "/AssertForwardServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/ForwardServlet?do=assertforward&foreign=%d2%e5%ec%ef%e5%f0%e0%f2%f3%f0%e0&test=1 HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Type: text/html\r
            Content-Length: 7\r
            Connection: close\r
            \r
            FORWARD""";
        assertEquals(expected, rawResponse);
    }

    @Test
    public void testForwardWithParam() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(EchoURIServlet.class, "/EchoURI/*");

        String responses = _connector.getResponse("""
            GET /context/ForwardServlet;ignore=true?do=req.echo&uri=EchoURI%2Fx%2520x%3Ba=1%3Fb=2 HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Type: text/plain\r
            Content-Length: 54\r
            Connection: close\r
            \r
            /context\r
            /EchoURI\r
            /x x\r
            /context/EchoURI/x%20x;a=1\r
            """;
        assertEquals(expected, responses);
    }

    @Test
    public void testNamedForward() throws Exception
    {
        ServletHolder holder = _contextHandler.getServletHandler().newServletHolder(Source.EMBEDDED);
        holder.setHeldClass(NamedForwardServlet.class);
        holder.setName("NamedForwardServlet"); //use easy-to-test name
        _contextHandler.addServlet(holder, "/forward/*");
        String echo = _contextHandler.addServlet(ForwardEchoURIServlet.class, "/echo/*").getName();

        String rawResponse = _connector.getResponse(("""
            GET /context/forward/info;param=value?name=@ECHO@ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """).replace("@ECHO@", echo));

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Type: text/plain\r
            Content-Length: 119\r
            Connection: close\r
            \r
            /context\r
            /forward\r
            /info\r
            /context/forward/info;param=value\r
            NamedForwardServlet\r
            null\r
            null\r
            null\r
            null\r
            null\r
            null\r
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testNamedInclude() throws Exception
    {
        _contextHandler.addServlet(NamedIncludeServlet.class, "/include/*");
        String echo = _contextHandler.addServlet(IncludeEchoURIServlet.class, "/echo/*").getName();

        String responses = _connector.getResponse("""
            GET /context/include/info;param=value?name=@ECHO@ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """.replace("@ECHO@", echo));

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 145\r
            Connection: close\r
            \r
            /context\r
            /include\r
            /info\r
            /context/include/info;param=value\r
            http://local/context/include/info;param=value\r
            null\r
            null\r
            null\r
            null\r
            null\r
            null\r
            """;

        assertEquals(expected, responses);
    }

    @Test
    public void testForwardWithBadParams() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(ServletChannel.class))
        {
            LOG.info("Expect Not valid UTF8 warnings...");
            _contextHandler.addServlet(AlwaysForwardServlet.class, "/forward/*");
            _contextHandler.addServlet(EchoServlet.class, "/echo/*");

            String rawResponse;

            rawResponse = _connector.getResponse("""
                GET /context/forward/?echo=allgood HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            assertThat(rawResponse, containsString(" 200 OK"));
            assertThat(rawResponse, containsString("allgood"));

            rawResponse = _connector.getResponse("""
                GET /context/forward/params?echo=allgood HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            assertThat(rawResponse, containsString(" 200 OK"));
            assertThat(rawResponse, containsString("allgood"));
            assertThat(rawResponse, containsString("forward"));

            rawResponse = _connector.getResponse("""
                GET /context/forward/badparams?echo=badparams HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            assertThat(rawResponse, containsString(" 500 "));

            rawResponse = _connector.getResponse("""
                GET /context/forward/?echo=badclient&bad=%88%A4 HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            assertThat(rawResponse, containsString(" 400 "));

            rawResponse = _connector.getResponse("""
                GET /context/forward/params?echo=badclient&bad=%88%A4 HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            assertThat(rawResponse, containsString(" 400 "));

            rawResponse = _connector.getResponse("""
                GET /context/forward/badparams?echo=badclientandparam&bad=%88%A4 HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            assertThat(rawResponse, containsString(" 400 "));
        }
    }

    @Test
    public void testInclude() throws Exception
    {
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(AssertIncludeServlet.class, "/AssertIncludeServlet/*");

        //test include, along with special extension to include that allows headers to
        //be set during an include
        String responses = _connector.getResponse("""
            GET /context/IncludeServlet?do=assertinclude&do=more&test=1&headers=true HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            specialSetHeader: specialSetHeader\r
            specialAddHeader: specialAddHeader\r
            Content-Length: 20\r
            Connection: close\r
            \r
            Include:
            INCLUDE---
            """;

        assertEquals(expected, responses);
    }

    @Test
    public void testServletIncludeWelcome() throws Exception
    {
        _server.stop();
        _contextHandler.setWelcomeFiles(new String[] {"index.x"});
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        ServletHolder defaultHolder = _contextHandler.addServlet(DefaultServlet.class, "/");
        defaultHolder.setInitParameter("welcomeServlets", "true");
        _contextHandler.addServlet(RogerThatServlet.class, "*.x");
        _server.start();

        String rawResponse = _connector.getResponse("""
            GET /context/r/ HTTP/1.0\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 11\r
            \r
            Roger That!""";

        assertEquals(expected, rawResponse);


        // direct include
        rawResponse = _connector.getResponse("""
            GET /context/dispatch/test?include=/index.x HTTP/1.0\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 11\r
            \r
            Roger That!""";

        assertEquals(expected, rawResponse);

        // include through welcome file based on servlet mapping
        rawResponse = _connector.getResponse("""
            GET /context/dispatch/test?include=/r/ HTTP/1.0\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 11\r
            \r
            Roger That!""";

        assertEquals(expected, rawResponse);
    }

    public static Stream<Arguments> includeTests()
    {
        return Stream.of(
            Arguments.of(false, false),
            Arguments.of(false, true),
            Arguments.of(true, false),
            Arguments.of(true, true)
        );
    }

    @ParameterizedTest
    @MethodSource("includeTests")
    public void testIncludeOutputStreamWriter(boolean includeWriter, boolean helloWriter) throws Exception
    {
        _contextHandler.addServlet(new ServletHolder(new IncludeServlet(includeWriter)), "/IncludeServlet/*");
        _contextHandler.addServlet(new ServletHolder(new HelloServlet(helloWriter)), "/Hello");

        //test include, along with special extension to include that allows headers to
        //be set during an include
        String responses = _connector.getResponse("""
            GET /context/IncludeServlet?do=hello HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        responses = responses.replaceFirst("Content-Length: .*\r\n", "");

        String expected = """
            HTTP/1.1 200 OK\r
            Connection: close\r
            \r
            Include:
            Hello
            ---
            """;

        assertEquals(expected, responses);
    }

    @Test
    public void testIncludeWriterOutputStream() throws Exception
    {
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(AssertIncludeServlet.class, "/AssertIncludeServlet/*");

        //test include, along with special extension to include that allows headers to
        //be set during an include
        String responses = _connector.getResponse("""
            GET /context/IncludeServlet?do=assertinclude&do=more&test=1&headers=true HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            specialSetHeader: specialSetHeader\r
            specialAddHeader: specialAddHeader\r
            Content-Length: 20\r
            Connection: close\r
            \r
            Include:
            INCLUDE---
            """;

        assertEquals(expected, responses);
    }

    @Test
    public void testIncludeStatic() throws Exception
    {
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(new ServletHolder("default", DefaultServlet.class), "/");
        _server.start();

        String responses = _connector.getResponse("""
            GET /context/IncludeServlet?do=static HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 31\r
            Connection: close\r
            \r
            Include:
            Test 2 to too two
            ---
            """;

        assertEquals(expected, responses);
    }

    @Test
    public void testIncludeStaticWithWriter() throws Exception
    {
        _contextHandler.addServlet(new ServletHolder(new IncludeServlet(true)), "/IncludeServlet/*");
        _contextHandler.addServlet(new ServletHolder("default", DefaultServlet.class), "/");
        _server.start();

        String responses = _connector.getResponse("""
            GET /context/IncludeServlet?do=static HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Connection: close\r
            \r
            Include:
            Test 2 to too two
            ---
            """;

        assertEquals(expected, responses);
    }

    @Test
    public void testForwardStatic() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(DefaultServlet.class, "/");
        _server.start();

        String responses = _connector.getResponse("""
            GET /context/ForwardServlet?do=req.echo&uri=/test.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        responses = responses.replaceFirst("Last-Modified: .*\r\n", "Last-Modified: xxx\r\n");

        String expected = """
            HTTP/1.1 200 OK\r
            Last-Modified: xxx\r
            Content-Type: text/plain\r
            Accept-Ranges: bytes\r
            Content-Length: 18\r
            Connection: close\r
            \r
            Test 2 to too two
            """;


        assertEquals(expected, responses);
    }

    @Test
    public void testForwardSendError() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/forward/*");
        _contextHandler.addServlet(SendErrorServlet.class, "/senderr/*");

        String forwarded = _connector.getResponse("""
            GET /context/forward?do=ctx.echo&uri=/senderr HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        assertThat(forwarded, containsString("HTTP/1.1 590 "));
        assertThat(forwarded, containsString("<h2>HTTP ERROR 590 Five Nine Zero</h2>"));
    }

    @Test
    public void testForwardExForwardEx() throws Exception
    {
        _contextHandler.addServlet(RelativeDispatch2Servlet.class, "/RelDispatchServlet/*");
        _contextHandler.addServlet(ThrowServlet.class, "/include/throw/*");

        String rawResponse = _connector.getResponse("""
            GET /context/RelDispatchServlet?path=include/throw HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 56\r
            Connection: close\r
            \r
            THROWING\r
            CAUGHT2 java.io.IOException: Expected\r
            AFTER\r
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testIncludeExIncludeEx() throws Exception
    {
        _contextHandler.addServlet(RelativeDispatch2Servlet.class, "/RelDispatchServlet/*");
        _contextHandler.addServlet(ThrowServlet.class, "/include/throw/*");

        String rawResponse = _connector.getResponse("""
            GET /context/RelDispatchServlet?include=true&path=include/throw HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 122\r
            Connection: close\r
            \r
            BEFORE\r
            THROWING\r
            CAUGHT1 java.io.IOException: Expected\r
            BETWEEN\r
            THROWING\r
            CAUGHT2 java.io.IOException: Expected\r
            AFTER\r
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testForwardThenInclude() throws Exception
    {
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(AssertForwardIncludeServlet.class, "/AssertForwardIncludeServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/ForwardServlet/forwardpath?do=include HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 20\r
            Connection: close\r
            \r
            Include:
            INCLUDE---
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testIncludeThenForward() throws Exception
    {
        _contextHandler.addServlet(IncludeServlet.class, "/IncludeServlet/*");
        _contextHandler.addServlet(ForwardServlet.class, "/ForwardServlet/*");
        _contextHandler.addServlet(AssertIncludeForwardServlet.class, "/AssertIncludeForwardServlet/*");

        String rawResponse = _connector.getResponse("""
            GET /context/IncludeServlet/includepath?do=forward HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 11\r
            Connection: close\r
            \r
            FORWARD---
            """;

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testServletForward() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/*");

        String rawResponse = _connector.getResponse("""
            GET /context/dispatch/test?forward=/roger/that HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 11\r
            Connection: close\r
            \r
            Roger That!""";

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testServletForwardDotDot() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/that");

        String rawRequest = """
            GET /context/dispatch/test?forward=/%2e%2e/roger/that HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;

        String rawResponse = _connector.getResponse(rawRequest);

        assertThat(rawResponse, startsWith("HTTP/1.1 404 "));
    }

    @Test
    public void testServletForwardEncodedDotDot() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/that");

        String rawRequest = """
            GET /context/dispatch/test?forward=/%252e%252e/roger/that HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;

        String rawResponse = _connector.getResponse(rawRequest);

        assertThat(rawResponse, startsWith("HTTP/1.1 404 "));
    }

    @Test
    public void testServletInclude() throws Exception
    {
        _contextHandler.addServlet(DispatchServletServlet.class, "/dispatch/*");
        _contextHandler.addServlet(RogerThatServlet.class, "/roger/*");

        String rawResponse = _connector.getResponse("""
            GET /context/dispatch/test?include=/roger/that HTTP/1.0\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        String expected = """
            HTTP/1.1 200 OK\r
            Content-Length: 11\r
            \r
            Roger That!""";

        assertEquals(expected, rawResponse);
    }

    @Test
    public void testForwardFilterToRogerServlet() throws Exception
    {
        _contextHandler.addServlet(RogerThatServlet.class, "/*");
        _contextHandler.addServlet(ReserveEchoServlet.class, "/recho/*");
        _contextHandler.addServlet(EchoServlet.class, "/echo/*");
        _contextHandler.addFilter(ForwardFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Response response;
        String rawResponse;

        rawResponse = _connector.getResponse("""
            GET /context/ HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("Roger That!"));

        rawResponse = _connector.getResponse("""
            GET /context/foo?echo=echoText HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("echoText"));

        rawResponse = _connector.getResponse("""
            GET /context/?echo=echoText HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("txeTohce"));
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
        _contextHandler.addServlet(new ServletHolder("DispatchServlet", AsyncDispatchTestServlet.class), "/DispatchServlet");

        HttpTester.Response response;
        String rawResponse;

        rawResponse = _connector.getResponse("""
            GET /context/DispatchServlet HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("matchValue=TestServlet, pattern=/TestServlet, servletName=TestServlet, mappingMatch=EXACT"));
    }

    @Test
    public void testDispatchMapping404() throws Exception
    {
        _contextHandler.addServlet(new ServletHolder("TestServlet", MappingServlet.class), "/TestServlet");
        _contextHandler.addServlet(new ServletHolder("DispatchServlet", AsyncDispatchTestServlet.class), "/DispatchServlet");

        ErrorPageErrorHandler errorPageErrorHandler = new ErrorPageErrorHandler();
        _contextHandler.setErrorHandler(errorPageErrorHandler);
        errorPageErrorHandler.addErrorPage(404, "/TestServlet");

        HttpTester.Response response;
        String rawResponse;

        // Test not found
        rawResponse = _connector.getResponse("""
            GET /context/DispatchServlet?target=/DoesNotExist HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getContent(), containsString("matchValue=TestServlet, pattern=/TestServlet, servletName=TestServlet, mappingMatch=EXACT"));
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
            else if (request.getParameter("do").equals("assertmultipart"))
                dispatcher = getServletContext().getRequestDispatcher("/AssertMultiPartForwardServlet?do=end&do=the");
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

    public static class ParameterReadingFilter implements Filter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            //case the params to be parsed on the request
            Map<String, String[]> params = request.getParameterMap();

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

            if (request.getParameter("forward") != null)
            {
                ServletContext foreign = getServletContext().getContext("/foreign");
                assertNotNull(foreign);
                dispatcher = foreign.getRequestDispatcher(request.getParameter("forward"));

                if (dispatcher != null)
                {
                    dispatcher.forward(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
                }
                else
                    response.sendError(404);
            }
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

/*          System.err.println(req.getAttribute("jakarta.servlet.forward.mapping"));
            System.err.println(req.getAttribute("jakarta.servlet.forward.request_uri"));
            System.err.println(req.getAttribute("jakarta.servlet.forward.context_path"));
            System.err.println(req.getAttribute("jakarta.servlet.forward.servlet_path"));
            System.err.println(req.getAttribute("jakarta.servlet.forward.path_info"));
            System.err.println(req.getAttribute("jakarta.servlet.forward.query_string"));*/
        }
    }

    public static class VerifyForwardServlet extends GenericServlet
    {
        @Override
        public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException
        {
            if (DispatcherType.FORWARD.equals(req.getDispatcherType()))
                res.getWriter().print("Verified!");
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
            response.getOutputStream().println(request.getRequestURL().toString());
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
            assertEquals("http://local/context/AssertForwardServlet", request.getRequestURL().toString());

            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().print(request.getDispatcherType().toString());
        }
    }

    public static class AssertMultiPartForwardServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/html");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().println("org.eclipse.jetty.multipartConfig = " + request.getAttribute("org.eclipse.jetty.multipartConfig"));
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
            assertEquals("http://local/context/IncludeServlet", request.getRequestURL().toString());
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
}
