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
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class ResponseHeadersTest
{
    private Server server;
    private LocalConnector connector;

    public void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(contextHandler);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testHeaders() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet testServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader("DeletedWithSetNullValue", "not-null");
                response.setHeader("DeletedWithSetNullValue", null);
                response.addHeader("IgnoredWithAddNullValue", null);

                response.setHeader("SetHeaderOnce", "Once");
                response.setHeader("SetHeaderTwice", "Once");
                response.setHeader("SetHeaderTwice", "Twice");

                response.addHeader("AddHeaderOnce", "Once");
                response.addHeader("AddHeaderTwice", "Once");
                response.addHeader("AddHeaderTwice", "Twice");

                response.flushBuffer();

                response.setHeader("SetAfterCommit", "ignored");
                response.addHeader("AddAfterCommit", "ignored");
                response.setHeader("AddHeaderTwice", "ignored");

                response.getOutputStream().print("OK");
            }
        };
        contextHandler.addServlet(testServlet, "/headers/*");

        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/headers/test");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("OK"));

        assertThat(response.getFieldNamesCollection(), not(hasItem("DeletedWithSetNullValue")));
        assertThat(response.getFieldNamesCollection(), not(hasItem("SetAfterCommit")));
        assertThat(response.getFieldNamesCollection(), not(hasItem("AddAfterCommit")));

        assertThat(response.get("SetHeaderOnce"), is("Once"));
        assertThat(response.get("SetHeaderTwice"), is("Twice"));
        assertThat(response.get("AddHeaderOnce"), is("Once"));
        assertThat(response.get("AddHeaderTwice"), is("Once"));
        assertThat(response.getValuesList("AddHeaderTwice"), contains("Once", "Twice"));
    }

    @Test
    public void testResponseWebSocketHeaderFormat() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet fakeWsServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader("Upgrade", "WebSocket");
                response.addHeader("Connection", "Upgrade");
                response.addHeader("Sec-WebSocket-Accept", "123456789==");

                response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            }
        };
        contextHandler.addServlet(fakeWsServlet, "/ws/*");

        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/ws/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(101));
        assertThat("Response Header Upgrade", response.get("Upgrade"), is("WebSocket"));
        assertThat("Response Header Connection", response.get("Connection"), is("Upgrade"));
    }

    @Test
    public void testMultilineResponseHeaderValue() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        HttpServlet multilineServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                // The bad use-case
                String pathInfo = URIUtil.decodePath(req.getPathInfo());
                if (pathInfo != null && pathInfo.length() > 1 && pathInfo.startsWith("/"))
                {
                    pathInfo = pathInfo.substring(1);
                }
                response.setHeader("X-example", pathInfo);

                // The correct use
                response.setContentType("text/plain");
                response.setCharacterEncoding("utf-8");
                response.getWriter().println("Got request uri - " + req.getRequestURI());
            }
        };

        contextHandler.addServlet(multilineServlet, "/multiline/*");
        startServer(contextHandler);
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);

        String actualPathInfo = "%0A%20Content-Type%3A%20image/png%0A%20Content-Length%3A%208%0A%20%0A%20yuck<!--";

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/multiline/" + actualPathInfo);
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("text/plain;charset=UTF-8"));

        String expected = StringUtil.replace(actualPathInfo, "%0A", " "); // replace OBS fold with space
        expected = URLDecoder.decode(expected, StandardCharsets.UTF_8); // decode the rest
        expected = expected.trim(); // trim whitespace at start/end
        assertThat("Response Header X-example", response.get("X-Example"), is(expected));
    }

    @Test
    public void testCharsetResetToJsonMimeType() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        HttpServlet charsetResetToJsonMimeTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // We set an initial desired behavior.
                response.setContentType("text/html; charset=US-ASCII");
                PrintWriter writer = response.getWriter();

                // We reset the response, as we don't want it to be HTML anymore.
                response.reset();

                // switch to json operation
                // The use of application/json is always assumed to be UTF-8
                // and should never have a `charset=` entry on the `Content-Type` response header
                response.setContentType("application/json");
                writer.println("{ \"what\": \"should this be?\" }");
            }
        };

        contextHandler.addServlet(charsetResetToJsonMimeTypeServlet, "/charset/json-reset/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/charset/json-reset/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The Content-Type should not have a charset= portion
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("application/json"));
    }

    @Test
    public void testCharsetChangeToJsonMimeType() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet charsetChangeToJsonMimeTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // We set an initial desired behavior.
                response.setContentType("text/html; charset=US-ASCII");

                // switch to json behavior.
                // The use of application/json is always assumed to be UTF-8
                // and should never have a `charset=` entry on the `Content-Type` response header
                response.setContentType("application/json");

                PrintWriter writer = response.getWriter();
                writer.println("{ \"what\": \"should this be?\" }");
            }
        };

        contextHandler.addServlet(charsetChangeToJsonMimeTypeServlet, "/charset/json-change/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/charset/json-change/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The Content-Type should not have a charset= portion
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("application/json"));
    }

    @Test
    public void testCharsetChangeToJsonMimeTypeSetCharsetToNull() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet charsetChangeToJsonMimeTypeSetCharsetToNullServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // We set an initial desired behavior.
                response.setContentType("text/html; charset=us-ascii");
                PrintWriter writer = response.getWriter();

                // switch to json behavior.
                // The use of application/json is always assumed to be UTF-8
                // and should never have a `charset=` entry on the `Content-Type` response header
                response.setContentType("application/json");
                // attempt to indicate that there is truly no charset meant to be used in the response header
                response.setCharacterEncoding(null);

                writer.println("{ \"what\": \"should this be?\" }");
            }
        };

        contextHandler.addServlet(charsetChangeToJsonMimeTypeSetCharsetToNullServlet, "/charset/json-change-null/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/charset/json-change-null/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The Content-Type should not have a charset= portion
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("application/json"));
    }

    @Test
    public void testAddSetHeaderNulls() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet addHeaderNullServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Add the header
                response.addHeader("X-Foo", "foo-value");

                // Add a null header value (should result in no change, and no exception)
                response.addHeader("X-Foo", null);

                // Add a null name (should result in no change, and no exception)
                response.addHeader(null, "bogus");

                // Add a null name and null value (should result in no change, and no exception)
                response.addHeader(null, null);

                // Set a new header
                response.setHeader("X-Bar", "bar-value");

                // Set same header with null (should remove header)
                response.setHeader("X-Bar", null);

                // Set a header with null name (should result in no change, and no exception)
                response.setHeader(null, "bogus");

                // Set a header with null name and null value (should result in no change, and no exception)
                response.setHeader(null, null);

                response.setCharacterEncoding("utf-8");
                response.setContentType("text/plain");

                PrintWriter writer = response.getWriter();
                writer.println("Done");
            }
        };

        contextHandler.addServlet(addHeaderNullServlet, "/add-header-nulls/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/add-header-nulls/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The X-Foo header should be present an unchanged
        assertThat("Response Header X-Foo", response.get("X-Foo"), is("foo-value"));
        assertThat("Response Header X-Bar should not exist", response.getField("X-Bar"), nullValue());
    }

    @Test
    public void testAddSetDateHeaderNulls() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        final long date = System.currentTimeMillis();
        HttpServlet addHeaderNullServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Add the header
                response.addDateHeader("X-Foo", date);
                String dateStr = response.getHeader("X-Foo"); // get the formatted Date as a String

                // Add a null name (should result in no change, and no exception)
                response.addDateHeader(null, 123456);

                // Set a null name (should result in no change, and no exception)
                response.setDateHeader(null, 987654);

                response.setCharacterEncoding("utf-8");
                response.setContentType("text/plain");

                PrintWriter writer = response.getWriter();
                writer.println(dateStr);
            }
        };

        contextHandler.addServlet(addHeaderNullServlet, "/add-dateheader-nulls/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/add-dateheader-nulls/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        String dateStr = response.getContent().trim();
        assertThat("Should have seen a Date String", dateStr, endsWith(" GMT"));
        // The X-Foo header should be present an unchanged
        assertThat("Response Header X-Foo", response.get("X-Foo"), is(dateStr));
    }

    @Test
    public void testAddSetIntHeaderNulls() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        final int foovalue = 22222222;
        HttpServlet addHeaderNullServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Add the header
                response.addIntHeader("X-Foo", foovalue);

                // Add a null name (should result in no change, and no exception)
                response.addIntHeader(null, 123456);

                // Set a null name (should result in no change, and no exception)
                response.setIntHeader(null, 987654);

                response.setCharacterEncoding("utf-8");
                response.setContentType("text/plain");

                PrintWriter writer = response.getWriter();
                writer.println("Done");
            }
        };

        contextHandler.addServlet(addHeaderNullServlet, "/add-intheader-nulls/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/add-intheader-nulls/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The X-Foo header should be present an unchanged
        assertThat("Response Header X-Foo", response.getField("X-Foo").getIntValue(), is(foovalue));
    }

    @Test
    public void testSetIntHeader() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet addHeaderServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.addIntHeader("X-Foo", 10);
                response.setIntHeader("X-Foo", 20);

                PrintWriter writer = response.getWriter();
                writer.println("Done");
            }
        };

        contextHandler.addServlet(addHeaderServlet, "/add-intheader/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/add-intheader/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The X-Foo header should be present an unchanged
        assertThat("Response Header X-Foo", response.getField("X-Foo").getIntValue(), is(20));
    }

    @Test
    public void testFlushPrintWriter() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet flushResponseServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                PrintWriter writer = response.getWriter();
                writer.println("Hello");
                writer.flush();
                if (!response.isCommitted())
                    throw new IllegalStateException();
                writer.println("World");
                writer.close();
            }
        };

        contextHandler.addServlet(flushResponseServlet, "/flush/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/flush");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response Code", response.getStatus(), is(200));
        assertThat(response.getContent(), equalTo("Hello\nWorld\n"));
    }

    @Test
    public void testContentTypeAfterWriterBeforeWrite() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet contentTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentType("text/xml;charset=ISO-8859-7");
                PrintWriter pw = response.getWriter();
                response.setContentType("text/html;charset=UTF-8");

                PrintWriter writer = response.getWriter();
                writer.println("Hello");
            }
        };

        contextHandler.addServlet(contentTypeServlet, "/content/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/content");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response Code", response.getStatus(), is(200));
        assertThat("Content Type", response.getField("Content-Type").getValue(), containsString("text/html;charset=ISO-8859-7"));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testContentTypeNoCharset() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet contentTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentType("text/html;charset=Shift_Jis");
                response.setContentType("text/xml");

                PrintWriter pw = response.getWriter();
                pw.println("Hello");
            }
        };

        contextHandler.addServlet(contentTypeServlet, "/content/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/content");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response Code", response.getStatus(), is(200));
        assertThat("Content Type", response.getField("Content-Type").getValue(), containsString("text/xml;charset=Shift_Jis"));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testContentTypeNull() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet contentTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentType("text/html;charset=Shift_Jis");
                response.setContentType(null);

                PrintWriter pw = response.getWriter();
                assertThat(response.getCharacterEncoding(), not(is("Shift_Jis")));
                pw.println("Hello");
            }
        };

        contextHandler.addServlet(contentTypeServlet, "/content/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/content");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response Code", response.getStatus(), is(200));
        assertThat("Content Type", response.getField("Content-Type"), nullValue());
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testCommittedNoop() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet addHeaderServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader("Test", "Before");
                response.setHeader("Content-Length", "2");
                response.setHeader("Content-Type", "text/html");

                response.getOutputStream().print("OK");
                response.flushBuffer();

                // These should be silently ignored
                response.setHeader("Test", "After");
                response.setHeader("Content-Length", "10");
                response.setHeader("Content-Type", "text/xml");

                assertThat(response.getHeader("Test"), is("Before"));
                assertThat(response.getContentType(), is("text/html"));
                assertThat(response.getHeader("Content-Length"), is("2"));
            }
        };

        contextHandler.addServlet(addHeaderServlet, "/test/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/test/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getField("Test").getValue(), is("Before"));
        assertThat(response.getField("Content-Type").getValue(), is("text/html"));
        assertThat(response.getContent(), is("OK"));
    }
}
