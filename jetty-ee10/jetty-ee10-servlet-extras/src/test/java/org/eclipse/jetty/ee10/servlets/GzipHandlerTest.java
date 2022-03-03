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

package org.eclipse.jetty.ee9.servlets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class GzipHandlerTest extends AbstractGzipTest
{
    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGzipCompressedByContentTypeWithEncoding() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setMinGzipSize(32);
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/context");
        contextHandler.addServlet(HttpContentTypeWithEncodingServlet.class, "/*");

        gzipHandler.setHandler(contextHandler);

        server.setHandler(gzipHandler);
        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/xxx");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[Vary]", response.get("Vary"), is("Accept-Encoding"));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response[Content] raw length vs uncompressed length", metadata.contentLength, not(is(metadata.uncompressedSize)));
        assertThat("(Uncompressed) Content", metadata.getContentUTF8(), is(HttpContentTypeWithEncodingServlet.CONTENT));
    }

    public static class HttpContentTypeWithEncodingServlet extends HttpServlet
    {
        public static final String CONTENT = "<html><head></head><body><h1>COMPRESSIBLE CONTENT</h1>" +
            "<p>" +
            "This content must be longer than the default min gzip length, which is " + GzipHandler.DEFAULT_MIN_GZIP_SIZE + " bytes. " +
            "The moon is blue to a fish in love. <br/>" +
            "How now brown cow. <br/>" +
            "The quick brown fox jumped over the lazy dog. <br/>" +
            "A woman needs a man like a fish needs a bicycle!" +
            "</p>" +
            "</body></html>";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain;charset=UTF8");
            resp.setStatus(200);
            ServletOutputStream out = resp.getOutputStream();
            out.print(CONTENT);
        }
    }

    @Test
    public void testIsNotGzipCompressedHttpStatus() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/context");
        contextHandler.addServlet(HttpStatusServlet.class, "/*");

        gzipHandler.setHandler(contextHandler);

        server.setHandler(gzipHandler);
        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/xxx");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.NO_CONTENT_204));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
    }

    public static class HttpStatusServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            resp.setHeader("ETag", "W/\"204\"");
        }
    }

    @Test
    public void testIsNotGzipCompressedHttpBadRequestStatus() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/context");
        contextHandler.addServlet(HttpErrorServlet.class, "/*");

        gzipHandler.setHandler(contextHandler);

        server.setHandler(gzipHandler);
        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/xxx");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.BAD_REQUEST_400));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response Content", response.getContent(), is("error message"));
    }

    public static class HttpErrorServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.getOutputStream().write("error message".getBytes());
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
    }
}
