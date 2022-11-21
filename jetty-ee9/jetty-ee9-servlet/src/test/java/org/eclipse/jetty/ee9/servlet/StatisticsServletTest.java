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

package org.eclipse.jetty.ee9.servlet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.nested.SessionHandler;
import org.eclipse.jetty.ee9.nested.StatisticsHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class StatisticsServletTest
{
    private Server _server;

    private LocalConnector _connector;

    @BeforeEach
    public void createServer()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void destroyServer()
        throws Exception
    {
        _server.stop();
        _server.join();
    }

    private void addStatisticsHandler()
    {
        ServletContextHandler statsContext = new ServletContextHandler(_server, "/");
        StatisticsHandler statsHandler = new StatisticsHandler();
        statsContext.insertHandler(statsHandler);
        statsContext.addServlet(new ServletHolder(new TestServlet()), "/test1");
        ServletHolder servletHolder = new ServletHolder(new StatisticsServlet());
        servletHolder.setInitParameter("restrictToLocalhost", "false");
        statsContext.addServlet(servletHolder, "/stats");
        statsContext.setSessionHandler(new SessionHandler());
    }

    @Test
    public void testGetStats()
        throws Exception
    {
        addStatisticsHandler();
        _server.start();

        HttpTester.Response response;

        // Trigger 2xx response
        response = getResponse("/test1");
        assertEquals(response.getStatus(), 200);

        // Look for 200 response that was tracked
        response = getResponse("/stats");
        assertEquals(response.getStatus(), 200);
        Stats stats = parseStats(response.getContent());

        assertEquals(1, stats.responses2xx);

        // Reset stats
        response = getResponse("/stats?statsReset=true");
        assertEquals(response.getStatus(), 200);

        // Request stats again
        response = getResponse("/stats");
        assertEquals(response.getStatus(), 200);
        stats = parseStats(response.getContent());

        assertEquals(1, stats.responses2xx);

        // Trigger 2xx response
        response = getResponse("/test1");
        assertEquals(response.getStatus(), 200);
        // Trigger 4xx response
        response = getResponse("/nothing");
        assertEquals(response.getStatus(), 404);

        // Request stats again
        response = getResponse("/stats");
        assertEquals(response.getStatus(), 200);
        stats = parseStats(response.getContent());

        // Verify we see (from last reset)
        // 1) request for /stats?statsReset=true [2xx]
        // 2) request for /stats?xml=true [2xx]
        // 3) request for /test1 [2xx]
        // 4) request for /nothing [4xx]
        assertThat("2XX Response Count" + response, stats.responses2xx, is(3));
        assertThat("4XX Response Count" + response, stats.responses4xx, is(1));
    }

    public static Stream<Arguments> typeVariations(String mimeType)
    {
        return Stream.of(
            Arguments.of(
                new Consumer<HttpTester.Request>()
                {
                    @Override
                    public void accept(HttpTester.Request request)
                    {
                        request.setURI("/stats");
                        request.setHeader("Accept", mimeType);
                    }

                    @Override
                    public String toString()
                    {
                        return "Header[Accept: " + mimeType + "]";
                    }
                }
            ),
            Arguments.of(
                new Consumer<HttpTester.Request>()
                {
                    @Override
                    public void accept(HttpTester.Request request)
                    {
                        request.setURI("/stats?accept=" + mimeType);
                    }

                    @Override
                    public String toString()
                    {
                        return "query[accept=" + mimeType + "]";
                    }
                }
            )
        );
    }

    public static Stream<Arguments> xmlVariations()
    {
        return typeVariations("text/xml");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("xmlVariations")
    public void testGetXmlResponse(Consumer<HttpTester.Request> requestCustomizer)
        throws Exception
    {
        addStatisticsHandler();
        _server.start();

        HttpTester.Response response;
        HttpTester.Request request = new HttpTester.Request();

        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");
        requestCustomizer.accept(request);

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response.contentType", response.get(HttpHeader.CONTENT_TYPE), containsString("text/xml"));

        // System.out.println(response.getContent());

        // Parse it, make sure it's well formed.
        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
        docBuilderFactory.setValidating(false);
        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        try (ByteArrayInputStream input = new ByteArrayInputStream(response.getContentBytes()))
        {
            Document doc = docBuilder.parse(input);
            assertNotNull(doc);
            assertEquals("statistics", doc.getDocumentElement().getNodeName());
        }
    }

    public static Stream<Arguments> jsonVariations()
    {
        return typeVariations("application/json");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("jsonVariations")
    public void testGetJsonResponse(Consumer<HttpTester.Request> requestCustomizer)
        throws Exception
    {
        addStatisticsHandler();
        _server.start();

        HttpTester.Response response;
        HttpTester.Request request = new HttpTester.Request();

        request.setMethod("GET");
        requestCustomizer.accept(request);
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response.contentType", response.get(HttpHeader.CONTENT_TYPE), is("application/json"));
        assertThat("Response.contentType for json should never contain a charset",
            response.get(HttpHeader.CONTENT_TYPE), not(containsString("charset")));

        // System.out.println(response.getContent());

        // Parse it, make sure it's well formed.
        Object doc = new JSON().parse(new JSON.StringSource(response.getContent()));
        assertNotNull(doc);
        assertThat(doc, instanceOf(Map.class));
        Map<?, ?> docMap = (Map<?, ?>)doc;
        assertEquals(4, docMap.size());
        assertNotNull(docMap.get("requests"));
        assertNotNull(docMap.get("responses"));
        assertNotNull(docMap.get("connections"));
        assertNotNull(docMap.get("memory"));
    }

    public static Stream<Arguments> plaintextVariations()
    {
        return typeVariations("text/plain");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("plaintextVariations")
    public void testGetTextResponse(Consumer<HttpTester.Request> requestCustomizer)
        throws Exception
    {
        addStatisticsHandler();
        _server.start();

        HttpTester.Response response;
        HttpTester.Request request = new HttpTester.Request();

        request.setMethod("GET");
        requestCustomizer.accept(request);
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response.contentType", response.get(HttpHeader.CONTENT_TYPE), containsString("text/plain"));

        // System.out.println(response.getContent());

        // Look for expected content
        assertThat(response.getContent(), containsString("requests: "));
        assertThat(response.getContent(), containsString("responses: "));
        assertThat(response.getContent(), containsString("connections: "));
        assertThat(response.getContent(), containsString("memory: "));
    }

    public static Stream<Arguments> htmlVariations()
    {
        return typeVariations("text/html");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("htmlVariations")
    public void testGetHtmlResponse(Consumer<HttpTester.Request> requestCustomizer)
        throws Exception
    {
        addStatisticsHandler();
        _server.start();

        HttpTester.Response response;
        HttpTester.Request request = new HttpTester.Request();

        request.setMethod("GET");
        requestCustomizer.accept(request);
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response.contentType", response.get(HttpHeader.CONTENT_TYPE), containsString("text/html"));

        // System.out.println(response.getContent());

        // Look for things that indicate it's a well formed HTML output
        assertThat(response.getContent(), containsString("<html>"));
        assertThat(response.getContent(), containsString("<body>"));
        assertThat(response.getContent(), containsString("<em>requests</em>: "));
        assertThat(response.getContent(), containsString("<em>responses</em>: "));
        assertThat(response.getContent(), containsString("<em>connections</em>: "));
        assertThat(response.getContent(), containsString("<em>memory</em>: "));
        assertThat(response.getContent(), containsString("</body>"));
        assertThat(response.getContent(), containsString("</html>"));
    }

    public HttpTester.Response getResponse(String path)
        throws Exception
    {
        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setHeader("Accept", "text/xml");
        request.setURI(path);
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        return HttpTester.parseResponse(responseBuffer);
    }

    public Stats parseStats(String xml)
        throws Exception
    {
        XPath xPath = XPathFactory.newInstance().newXPath();

        String responses4xx = xPath.evaluate("//responses4xx", new InputSource(new StringReader(xml)));
        String responses2xx = xPath.evaluate("//responses2xx", new InputSource(new StringReader(xml)));

        return new Stats(Integer.parseInt(responses2xx), Integer.parseInt(responses4xx));
    }

    public static class Stats
    {
        int responses2xx;
        int responses4xx;

        public Stats(int responses2xx, int responses4xx)
        {
            this.responses2xx = responses2xx;
            this.responses4xx = responses4xx;
        }
    }

    public static class TestServlet
        extends HttpServlet
    {

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter writer = resp.getWriter();
            writer.write("Yup!!");
        }
    }
}
