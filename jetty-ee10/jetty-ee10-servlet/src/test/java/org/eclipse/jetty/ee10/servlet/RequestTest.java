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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestTest
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestTest.class);
    private Server _server;
    private LocalConnector _connector;

    private void startServer(HttpServlet servlet) throws Exception
    {
        startServer(null, servlet);
    }

    private void startServer(Consumer<Server> configureServer, HttpServlet servlet) throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());

        _connector = new LocalConnector(_server, http);
        _server.addConnector(_connector);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.addServlet(servlet, "/*").getRegistration().setMultipartConfig(new MultipartConfigElement("here"));

        _server.setHandler(servletContextHandler);

        if (configureServer != null)
            configureServer.accept(_server);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testCaseInsensitiveHeaders() throws Exception
    {
        final AtomicReference<Boolean> resultIsSecure = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse resp)
            {
                assertThat(request.getHeader("accept"), is("*/*"));
                assertThat(request.getHeader("Accept"), is("*/*"));
                assertThat(request.getHeader("ACCEPT"), is("*/*"));
                assertThat(request.getHeader("AcCePt"), is("*/*"));

                assertThat(request.getHeader("random"), is("value"));
                assertThat(request.getHeader("Random"), is("value"));
                assertThat(request.getHeader("RANDOM"), is("value"));
                assertThat(request.getHeader("rAnDoM"), is("value"));
                assertThat(request.getHeader("RaNdOm"), is("value"));

                assertThat(request.getHeader("Accept-Charset"), is("UTF-8"));
                assertThat(request.getHeader("accept-charset"), is("UTF-8"));

                assertThat(Collections.list(request.getHeaders("Accept-Charset")), contains("UTF-8", "UTF-16"));
                assertThat(Collections.list(request.getHeaders("accept-charset")), contains("UTF-8", "UTF-16"));

                assertThat(request.getHeader("foo-bar"), is("one"));
                assertThat(request.getHeader("Foo-Bar"), is("one"));
                assertThat(Collections.list(request.getHeaders("foo-bar")), contains("one", "two"));
                assertThat(Collections.list(request.getHeaders("Foo-Bar")), contains("one", "two"));

                assertThat(Collections.list(request.getHeaderNames()),
                    contains("Host", "Connection", "Accept", "RaNdOm", "Accept-Charset", "Foo-Bar"));
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET / HTTP/1.1
                Host: local
                Connection: close
                Accept: */*
                RaNdOm: value
                Accept-Charset: UTF-8
                accept-charset: UTF-16
                Foo-Bar: one
                foo-bar: two
                
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testIsSecure() throws Exception
    {
        final AtomicReference<Boolean> resultIsSecure = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultIsSecure.set(request.isSecure());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET / HTTP/1.1
                Host: local
                Connection: close
                X-Forwarded-Proto: https
                
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.isSecure", resultIsSecure.get(), is(true));
    }

    @Test
    public void testConnectRequestURLSameAsHost() throws Exception
    {
        final AtomicReference<String> resultRequestURL = new AtomicReference<>();
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURL.set(request.getRequestURL().toString());
                resultRequestURI.set(request.getRequestURI());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: myhost:9999\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURL", resultRequestURL.get(), is("http://myhost:9999/"));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/"));
    }

    @Test
    public void testConnectRequestURLDifferentThanHost() throws Exception
    {
        final AtomicReference<String> resultRequestURL = new AtomicReference<>();
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURL.set(request.getRequestURL().toString());
                resultRequestURI.set(request.getRequestURI());
            }
        });

        // per spec, "Host" is ignored if request-target is authority-form
        String rawResponse = _connector.getResponse(
            """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: otherhost:8888\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURL", resultRequestURL.get(), is("http://myhost:9999/"));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/"));
    }

    @Test
    public void testAmbiguousURI() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                count.incrementAndGet();
                String requestURI = request.getRequestURI();
                String servletPath;
                String pathInfo;
                try
                {
                    servletPath = request.getServletPath();
                }
                catch (IllegalArgumentException iae)
                {
                    servletPath = iae.toString();
                }
                try
                {
                    pathInfo = request.getPathInfo();
                }
                catch (IllegalArgumentException iae)
                {
                    pathInfo = iae.toString();
                }

                resp.getOutputStream().println("requestURI=%s servletPath=%s pathInfo=%s".formatted(requestURI, servletPath, pathInfo));
            }
        });

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        String rawRequest = """
            GET /test/foo%2fbar HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;
        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
        assertThat(count.get(), equalTo(0));

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        rawResponse = _connector.getResponse(rawRequest);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("requestURI=/test/foo%2fbar"));
        assertThat(response.getContent(), containsString("servletPath=org.eclipse.jetty.http.HttpException$IllegalArgumentException: 400: Ambiguous URI encoding"));
        assertThat(response.getContent(), containsString("pathInfo=org.eclipse.jetty.http.HttpException$IllegalArgumentException: 400: Ambiguous URI encoding"));
        assertThat(count.get(), equalTo(1));

        _server.getContainedBeans(ServletHandler.class).iterator().next().setDecodeAmbiguousURIs(true);
        rawResponse = _connector.getResponse(rawRequest);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("requestURI=/test/foo%2fbar"));
        assertThat(response.getContent(), containsString("servletPath= "));
        assertThat(response.getContent(), containsString("pathInfo=/test/foo/bar"));
        assertThat(count.get(), equalTo(2));
    }

    @Test
    public void testGetWithEncodedURI() throws Exception
    {
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();
        final AtomicReference<String> resultPathInfo = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURI.set(request.getRequestURI());
                resultPathInfo.set(request.getPathInfo());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET /test/path%20info/foo%2cbar HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/test/path%20info/foo%2cbar"));
        assertThat("request.getPathInfo", resultPathInfo.get(), is("/test/path info/foo,bar"));
    }

    @Test
    public void testCachedServletCookies() throws Exception
    {
        final List<Cookie> cookieHistory = new ArrayList<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                Cookie[] cookies = request.getCookies();
                if (cookies != null)
                    cookieHistory.addAll(Arrays.asList(cookies));
            }
        });

        try (LocalConnector.LocalEndPoint connection = _connector.connect())
        {
            connection.addInput("""
                GET /one HTTP/1.1\r
                Host: myhost\r
                Cookie: name1=value1; name2=value2\r
                \r
                GET /two HTTP/1.1\r
                Host: myhost\r
                Cookie: name1=value1; name2=value2\r
                \r
                GET /three HTTP/1.1\r
                Host: myhost\r
                Cookie: name1=value1; name3=value3\r
                Connection: close\r
                \r
                """);

            assertThat(connection.getResponse(), containsString(" 200 OK"));
            assertThat(connection.getResponse(), containsString(" 200 OK"));
            assertThat(connection.getResponse(), containsString(" 200 OK"));
        }

        assertThat(cookieHistory.size(), is(6));
        assertThat(cookieHistory.stream().map(c -> c.getName() + "=" + c.getValue()).toList(), contains(
            "name1=value1",
            "name2=value2",
            "name1=value1",
            "name2=value2",
            "name1=value1",
            "name3=value3"
        ));

        assertThat(cookieHistory.get(0), sameInstance(cookieHistory.get(2)));
        assertThat(cookieHistory.get(2), not(sameInstance(cookieHistory.get(4))));
    }

    @Test
    public void testAttributes() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                ServletContextRequest servletContextRequest = ServletContextRequest.getServletContextRequest(request);
                Request coreRequest = servletContextRequest.getRequest();

                // Set some fake SSL attributes
                X509Certificate[] certificates = new X509Certificate[0];
                coreRequest.setAttribute(EndPoint.SslSessionData.ATTRIBUTE,
                    EndPoint.SslSessionData.from(null, "identity", "quantum_42_Knowledge", certificates));

                // Check we have all the attribute names in servlet API
                Set<String> names = new HashSet<>(Collections.list(request.getAttributeNames()));
                assertThat(names, containsInAnyOrder(
                    EndPoint.SslSessionData.ATTRIBUTE,
                    ServletContextRequest.SSL_CIPHER_SUITE,
                    ServletContextRequest.SSL_KEY_SIZE,
                    ServletContextRequest.SSL_SESSION_ID,
                    ServletContextRequest.PEER_CERTIFICATES,
                    FormFields.MAX_FIELDS_ATTRIBUTE,
                    FormFields.MAX_LENGTH_ATTRIBUTE,
                    ServletContextRequest.MULTIPART_CONFIG_ELEMENT
                ));

                // check we can get the expected values
                assertThat(request.getAttribute(ServletContextRequest.SSL_CIPHER_SUITE), is("quantum_42_Knowledge"));
                assertThat(request.getAttribute(ServletContextRequest.SSL_KEY_SIZE), is(42));
                assertThat(request.getAttribute(ServletContextRequest.SSL_SESSION_ID), is("identity"));
                assertThat(request.getAttribute(ServletContextRequest.PEER_CERTIFICATES), sameInstance(certificates));
                assertThat(request.getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT), notNullValue());
                int maxFormKeys = ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormKeys();
                assertThat(request.getAttribute(FormFields.MAX_FIELDS_ATTRIBUTE), is(maxFormKeys));
                int maxFormContentSize = ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormContentSize();
                assertThat(request.getAttribute(FormFields.MAX_LENGTH_ATTRIBUTE), is(maxFormContentSize));

                // check we can set all those attributes in the servlet API
                request.setAttribute(ServletContextRequest.SSL_CIPHER_SUITE, "pig_33_latin");
                request.setAttribute(ServletContextRequest.SSL_KEY_SIZE, 3);
                request.setAttribute(ServletContextRequest.SSL_SESSION_ID, "other");
                request.setAttribute(ServletContextRequest.PEER_CERTIFICATES, "certificate");
                request.setAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT, "config2");
                request.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 101);
                request.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, 102);

                // check we can get the updated values
                assertThat(request.getAttribute(ServletContextRequest.SSL_CIPHER_SUITE), is("pig_33_latin"));
                assertThat(request.getAttribute(ServletContextRequest.SSL_KEY_SIZE), is(3));
                assertThat(request.getAttribute(ServletContextRequest.SSL_SESSION_ID), is("other"));
                assertThat(request.getAttribute(ServletContextRequest.PEER_CERTIFICATES), is("certificate"));
                assertThat(request.getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT), is("config2"));
                assertThat(request.getAttribute(FormFields.MAX_FIELDS_ATTRIBUTE), is(101));
                assertThat(request.getAttribute(FormFields.MAX_LENGTH_ATTRIBUTE), is(102));

                // but shared values are not changed
                assertThat(servletContextRequest.getMatchedResource().getResource().getServletHolder().getMultipartConfigElement(), notNullValue());
                assertThat(ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormKeys(), is(maxFormKeys));
                assertThat(ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormContentSize(), is(maxFormContentSize));

                // Check we can remove all the attributes
                request.removeAttribute(ServletContextRequest.SSL_CIPHER_SUITE);
                request.removeAttribute(ServletContextRequest.SSL_KEY_SIZE);
                request.removeAttribute(ServletContextRequest.SSL_SESSION_ID);
                request.setAttribute(ServletContextRequest.PEER_CERTIFICATES, null);
                request.removeAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT);
                request.removeAttribute(FormFields.MAX_FIELDS_ATTRIBUTE);
                request.removeAttribute(FormFields.MAX_LENGTH_ATTRIBUTE);
                request.removeAttribute(EndPoint.SslSessionData.ATTRIBUTE);

                assertThat(Collections.list(request.getAttributeNames()), empty());

                // but shared values are not changed
                assertThat(servletContextRequest.getMatchedResource().getResource().getServletHolder().getMultipartConfigElement(), notNullValue());
                assertThat(ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormKeys(), is(maxFormKeys));
                assertThat(ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormContentSize(), is(maxFormContentSize));

                resp.getWriter().println("OK");
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET /test HTTP/1.1\r
                Host: host\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("OK"));
    }

    @Test
    public void testGetCharacterEncoding() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                // No character encoding specified
                request.getReader();
                // Try setting after read has been obtained
                request.setCharacterEncoding("ISO-8859-2");
                assertThat(request.getCharacterEncoding(), nullValue());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET /test HTTP/1.1\r
                Host: host\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testUnknownCharacterEncoding() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                assertThat(request.getCharacterEncoding(), is("Unknown"));
                Assertions.assertThrows(UnsupportedEncodingException.class, request::getReader);
            }
        });

        String rawResponse = _connector.getResponse(
            """
                POST /test HTTP/1.1\r
                Host: host\r
                Content-Type:text/plain; charset=Unknown\r
                Content-Length: 10\r
                Connection: close\r
                \r
                1234567890\r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    public static Stream<Arguments> requestHostHeaders()
    {
        List<String> hostHeader = new ArrayList<>();
        hostHeader.add("localhost");
        hostHeader.add("127.0.0.1");
        try
        {
            InetAddress localhost = InetAddress.getLocalHost();
            hostHeader.add(localhost.getHostName());
            hostHeader.add(localhost.getHostAddress());
        }
        catch (UnknownHostException e)
        {
            LOG.debug("Unable to obtain InetAddress.LocalHost", e);
        }
        return hostHeader.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("requestHostHeaders")
    public void testDateResponseHeader(String hostHeader) throws Exception
    {
        startServer(
            (server) ->
            {
                HttpConfiguration httpConfiguration = new HttpConfiguration();
                // httpConfiguration.setSendDateHeader(true);
                ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
                connector.setPort(0);
                server.addConnector(connector);
            },
            new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
                {
                    resp.setCharacterEncoding("utf-8");
                    resp.setContentType("text/plain");
                    resp.getWriter().println("Foo");
                }
            });

        URI serverURI = _server.getURI();

        String rawRequest = """
            GET /foo HTTP/1.1
            Host: %s
            Connection: close
            
            """.formatted(hostHeader);

        try (Socket client = new Socket(hostHeader, serverURI.getPort()))
        {
            OutputStream out = client.getOutputStream();
            out.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = client.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            String date = response.get(HttpHeader.DATE);
            assertNotNull(date);
            // asserting an exact value is tricky as the Date header is dynamic,
            // so we just assert that it has some content and isn't blank
            assertTrue(StringUtil.isNotBlank(date));
            assertThat(date, containsString(","));
            assertThat(date, containsString(":"));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', useHeadersInDisplayName = false,
        textBlock = """
        # query         | expectedName | expectedValue
        a=bad_%e0%b     | a            | bad_�
        a=bad_%e0%b&b=2 | a            | bad_�
        a=bad_%e0%ba    | a            | bad_�
        b=short%a       | b            | short%a
        c=%%TOK%%       | c            | %%TOK%%
        """)
    public void testBadUtf8Query(String query, String expectedName, String expectedValue) throws Exception
    {
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse resp)
            {
                String param = request.getParameter(expectedName);
                assertThat(param, is(expectedValue));
                resp.setStatus(200);
            }
        };

        startServer((server) ->
                _connector.getConnectionFactory(HttpConnectionFactory.class)
                    .getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT.with("test", UriCompliance.Violation.BAD_UTF8_ENCODING, UriCompliance.Violation.TRUNCATED_UTF8_ENCODING, UriCompliance.Violation.BAD_PERCENT_ENCODING)),
            servlet
        );

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = """
            GET /?@QUERY@ HTTP/1.1\r
            Host: whatever\r
            Connection: close
            
            """.replaceAll("@QUERY@", query);

        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(200));
    }

    @Test
    public void testParameters() throws Exception
    {
        final AtomicReference<String> parameterMap = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                parameterMap.set(request.getParameterMap().toString());
                PrintWriter out = resp.getWriter();
                out.println(request.getParameter("a"));
                out.println(request.getParameterValues("a")[1]);
                out.println(request.getParameterValues("a")[2]);
                out.println(Arrays.asList(request.getParameterValues("b")));
                out.println(Arrays.asList(request.getParameterValues("c")));
                out.println(Arrays.asList(request.getParameterValues("d")));

            }
        });

        String rawResponse = _connector.getResponse(
            """
                POST /test/parameters?a=1&a=2&b=one&c= HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                Content-Type: application/x-www-form-urlencoded\r
                Content-Length: 23\r
                \r
                a=3&b=two&b=three&d=xyz\r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(parameterMap.get(), is("{a=[1, 2, 3],b=[one, two, three],c=[],d=[xyz]}"));
        assertThat(response.getContent().replaceAll("\r\n", "\n"), is("""
            1
            2
            3
            [one, two, three]
            []
            [xyz]
            """));
    }

    static Stream<Arguments> suspiciousCharactersLegacy()
    {
        return Stream.of(
            Arguments.of(UriCompliance.DEFAULT, "o", "o"),
            Arguments.of(UriCompliance.DEFAULT, "%5C", "400"),
            Arguments.of(UriCompliance.DEFAULT, "%0A", "400"),
            Arguments.of(UriCompliance.DEFAULT, "%00", "400"),
            Arguments.of(UriCompliance.DEFAULT, "%01", "400"),
            Arguments.of(UriCompliance.DEFAULT, "%5F", "_"),
            Arguments.of(UriCompliance.DEFAULT, "%2F", "400"),
            Arguments.of(UriCompliance.DEFAULT, "%252F", "400"),
            Arguments.of(UriCompliance.DEFAULT, "//", "400"),

            // these results are from jetty-11 DEFAULT
            Arguments.of(UriCompliance.JETTY_11, "o", "o"),
            Arguments.of(UriCompliance.JETTY_11, "%5C", "\\"),
            Arguments.of(UriCompliance.JETTY_11, "%0A", "\n"),
            Arguments.of(UriCompliance.JETTY_11, "%00", "400"),
            Arguments.of(UriCompliance.JETTY_11, "%01", "\u0001"),
            Arguments.of(UriCompliance.JETTY_11, "%5F", "_"),
            Arguments.of(UriCompliance.JETTY_11, "%2F", "/"),
            Arguments.of(UriCompliance.JETTY_11, "%252F", "%2F"),
            Arguments.of(UriCompliance.JETTY_11, "//", "400"),

            // these results are from jetty-11 LEGACY
            Arguments.of(UriCompliance.LEGACY, "o", "o"),
            Arguments.of(UriCompliance.LEGACY, "%5C", "\\"),
            Arguments.of(UriCompliance.LEGACY, "%0A", "\n"),
            Arguments.of(UriCompliance.LEGACY, "%00", "400"),
            Arguments.of(UriCompliance.LEGACY, "%01", "\u0001"),
            Arguments.of(UriCompliance.LEGACY, "%5F", "_"),
            Arguments.of(UriCompliance.LEGACY, "%2F", "/"),
            Arguments.of(UriCompliance.LEGACY, "%252F", "%2F"),
            Arguments.of(UriCompliance.LEGACY, "//", "//")
        );
    }

    @ParameterizedTest
    @MethodSource("suspiciousCharactersLegacy")
    public void testSuspiciousCharactersLegacy(UriCompliance compliance, String suspect, String expected) throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                if (expected.length() != 3 || !Character.isDigit(expected.charAt(0)))
                    assertThat(request.getPathInfo(), is("/test/fo" + expected + "bar"));
            }
        });

        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(compliance);
        if (compliance != UriCompliance.DEFAULT)
            _server.getBean(ServletContextHandler.class).getServletHandler().setDecodeAmbiguousURIs(true);
        String request = "GET /test/fo" + suspect + "bar HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        String response = _connector.getResponse(request);

        if (expected.length() == 3 && Character.isDigit(expected.charAt(0)))
        {
            assertThat(response, startsWith("HTTP/1.1 " + expected + " "));
        }
        else
        {
            assertThat(response, startsWith("HTTP/1.1 200 OK"));
        }
    }
}
