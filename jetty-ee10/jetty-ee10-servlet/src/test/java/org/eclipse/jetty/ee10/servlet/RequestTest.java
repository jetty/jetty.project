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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

public class RequestTest
{
    private Server _server;
    private LocalConnector _connector;

    private void startServer(HttpServlet servlet) throws Exception
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
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(_server);
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
                Object certificate = new Object();
                coreRequest.setAttribute(SecureRequestCustomizer.CIPHER_SUITE_ATTRIBUTE, "quantumKnowledge");
                coreRequest.setAttribute(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE, 42);
                coreRequest.setAttribute(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE, "identity");
                coreRequest.setAttribute(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE, certificate);

                // Check we have all the attribute names in servlet API
                Set<String> names = new HashSet<>(Collections.list(request.getAttributeNames()));
                assertThat(names, containsInAnyOrder(
                    SecureRequestCustomizer.CIPHER_SUITE_ATTRIBUTE,
                    "jakarta.servlet.request.cipher_suite",
                    SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE,
                    "jakarta.servlet.request.key_size",
                    SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE,
                    "jakarta.servlet.request.ssl_session_id",
                    SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE,
                    "jakarta.servlet.request.X509Certificate",
                    FormFields.MAX_FIELDS_ATTRIBUTE,
                    FormFields.MAX_LENGTH_ATTRIBUTE,
                    ServletContextRequest.MULTIPART_CONFIG_ELEMENT
                ));

                // check we can get the expected values
                assertThat(request.getAttribute(SecureRequestCustomizer.CIPHER_SUITE_ATTRIBUTE), is("quantumKnowledge"));
                assertThat(request.getAttribute("jakarta.servlet.request.cipher_suite"), is("quantumKnowledge"));
                assertThat(request.getAttribute(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE), is(42));
                assertThat(request.getAttribute("jakarta.servlet.request.key_size"), is(42));
                assertThat(request.getAttribute(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE), is("identity"));
                assertThat(request.getAttribute("jakarta.servlet.request.ssl_session_id"), is("identity"));
                assertThat(request.getAttribute(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE), sameInstance(certificate));
                assertThat(request.getAttribute("jakarta.servlet.request.X509Certificate"), sameInstance(certificate));
                assertThat(request.getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT), notNullValue());
                int maxFormKeys = ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormKeys();
                assertThat(request.getAttribute(FormFields.MAX_FIELDS_ATTRIBUTE), is(maxFormKeys));
                int maxFormContentSize = ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormContentSize();
                assertThat(request.getAttribute(FormFields.MAX_LENGTH_ATTRIBUTE), is(maxFormContentSize));

                // check we can set all those attributes in the servlet API
                request.setAttribute("jakarta.servlet.request.cipher_suite", "piglatin");
                request.setAttribute(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE, 3);
                request.setAttribute(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE, "other");
                request.setAttribute("jakarta.servlet.request.X509Certificate", "certificate");
                request.setAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT, "config2");
                request.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 101);
                request.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, 102);

                // check we can get the updated values
                assertThat(request.getAttribute(SecureRequestCustomizer.CIPHER_SUITE_ATTRIBUTE), is("piglatin"));
                assertThat(request.getAttribute("jakarta.servlet.request.cipher_suite"), is("piglatin"));
                assertThat(request.getAttribute(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE), is(3));
                assertThat(request.getAttribute("jakarta.servlet.request.key_size"), is(3));
                assertThat(request.getAttribute(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE), is("other"));
                assertThat(request.getAttribute("jakarta.servlet.request.ssl_session_id"), is("other"));
                assertThat(request.getAttribute(SecureRequestCustomizer.PEER_CERTIFICATES_ATTRIBUTE), is("certificate"));
                assertThat(request.getAttribute("jakarta.servlet.request.X509Certificate"), is("certificate"));
                assertThat(request.getAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT), is("config2"));
                assertThat(request.getAttribute(FormFields.MAX_FIELDS_ATTRIBUTE), is(101));
                assertThat(request.getAttribute(FormFields.MAX_LENGTH_ATTRIBUTE), is(102));

                // but shared values are not changed
                assertThat(servletContextRequest.getMatchedResource().getResource().getServletHolder().getMultipartConfigElement(), notNullValue());
                assertThat(ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormKeys(), is(maxFormKeys));
                assertThat(ServletContextHandler.getServletContextHandler(request.getServletContext()).getMaxFormContentSize(), is(maxFormContentSize));

                // Check we can remove all the attributes
                request.removeAttribute("jakarta.servlet.request.cipher_suite");
                request.removeAttribute(SecureRequestCustomizer.KEY_SIZE_ATTRIBUTE);
                request.setAttribute(SecureRequestCustomizer.SSL_SESSION_ID_ATTRIBUTE, null);
                request.setAttribute("jakarta.servlet.request.X509Certificate", null);
                request.removeAttribute(ServletContextRequest.MULTIPART_CONFIG_ELEMENT);
                request.removeAttribute(FormFields.MAX_FIELDS_ATTRIBUTE);
                request.removeAttribute(FormFields.MAX_LENGTH_ATTRIBUTE);

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
}
