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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Test the GzipHandler support when working with the {@link DefaultServlet}.
 */
public class GzipDefaultServletTest extends AbstractGzipTest
{
    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "WIBBLE", "GET", "HEAD"})
    public void testIsGzipByMethod(String method) throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setIncludedMethods("POST", "WIBBLE", "GET", "HEAD");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", WibbleDefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 8;

        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod(method); // The point of this test
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[ETag]", response.get("ETag"), startsWith("W/"));
        assertThat("Response[ETag]", response.get("ETag"), containsString(CompressedContentFormat.GZIP.getEtagSuffix()));

        assertThat("Response[Content-Length]", response.get("Content-Length"), is(nullValue()));
        // A HEAD request should have similar headers, but no body
        if (!method.equals("HEAD"))
        {
            assertThat("Response[Content-Length]", response.get("Content-Length"), is(nullValue()));
            // Response Content checks
            UncompressedMetadata metadata = parseResponseContent(response);
            assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
            assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
        }
    }

    public static class WibbleDefaultServlet extends DefaultServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            switch (req.getMethod())
            {
                case "WIBBLE":
                    // Disregard the method given, use GET instead.
                    doGet(req, resp);
                    return;
                default:
                    super.service(req, resp);
            }
        }
    }

    @Test
    public void testIsGzipCompressedEmpty() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = 0;
        createFile(contextDir, "file.txt", fileSize);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(0));
    }

    public static Stream<Integer> compressibleSizes()
    {
        return Stream.of(
            DEFAULT_OUTPUT_BUFFER_SIZE / 4,
            DEFAULT_OUTPUT_BUFFER_SIZE,
            DEFAULT_OUTPUT_BUFFER_SIZE * 4);
    }

    @ParameterizedTest
    @MethodSource("compressibleSizes")
    public void testIsGzipCompressed(int fileSize) throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[Vary]", response.get("Vary"), containsString("Accept-Encoding"));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @ParameterizedTest
    @MethodSource("compressibleSizes")
    public void testIsGzipCompressedIfModifiedSince(int fileSize) throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        long fourSecondsAgo = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - 4000;
        request.setHeader("If-Modified-Since", DateGenerator.formatDate(fourSecondsAgo));
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[ETag]", response.get("ETag"), startsWith("W/"));
        assertThat("Response[ETag]", response.get("ETag"), containsString(CompressedContentFormat.GZIP.getEtagSuffix()));
        assertThat("Response[Vary]", response.get("Vary"), containsString("Accept-Encoding"));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testGzippedIfSVG() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("image/svg+xml");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        Path testResource = MavenTestingUtils.getTestResourcePath("test.svg");
        Path file = contextDir.resolve("test.svg");
        IO.copy(testResource.toFile(), file.toFile());
        String expectedSha1Sum = Sha1Sum.calculate(testResource);
        int fileSize = (int)Files.size(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/test.svg");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[Vary]", response.get("Vary"), containsString("Accept-Encoding"));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testNotGzipedIfNotModified() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        createFile(contextDir, "file.txt", fileSize);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        long fiveMinutesLater = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        request.setHeader("If-Modified-Since", DateGenerator.formatDate(fiveMinutesLater));
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[ETag]", response.get("ETag"), startsWith("W/"));
        assertThat("Response[ETag]", response.get("ETag"), not(containsString(CompressedContentFormat.GZIP.getEtagSuffix())));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(0));
    }

    /**
     * Gzip incorrectly gzips when {@code Accept-Encoding: gzip; q=0}.
     *
     * <p>
     * A quality of 0 results in no compression.
     * </p>
     *
     * See: http://bugs.eclipse.org/388072
     */
    @Test
    public void testIsNotGzipCompressedWithZeroQ() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE / 4;
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip; q=0"); // TESTING THIS
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), containsString("Accept-Encoding"));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testIsGzipCompressedWithQ() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE / 4;
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "something; q=0.1, gzip; q=0.5"); // TESTING THIS
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[Vary]", response.get("Vary"), containsString("Accept-Encoding"));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testIsNotGzipCompressedByContentType() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = createFile(contextDir, "file.mp3", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.mp3");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testIsNotGzipCompressedByExcludedContentType() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addExcludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testIsNotGzipCompressedByExcludedContentTypeWithCharset() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addExcludedMimeTypes("text/plain");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        servletContextHandler.getMimeTypes().addMimeMapping("txt", "text/plain;charset=UTF-8");
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = createFile(contextDir, "test_quotes.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/test_quotes.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testExcludePaths() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addIncludedMimeTypes("text/plain");
        gzipHandler.setExcludedPaths("*.txt");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testIncludedPaths() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setExcludedPaths("/bad.txt");
        gzipHandler.setIncludedPaths("*.txt");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        Path fileGood = createFile(contextDir, "file.txt", DEFAULT_OUTPUT_BUFFER_SIZE * 4);
        Path fileBad = createFile(contextDir, "bad.txt", DEFAULT_OUTPUT_BUFFER_SIZE * 2);
        String expectedGoodSha1Sum = Sha1Sum.calculate(fileGood);
        String expectedBadSha1Sum = Sha1Sum.calculate(fileBad);

        server.start();

        // Test Request 1
        {
            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod("GET");
            request.setVersion(HttpVersion.HTTP_1_1);
            request.setHeader("Host", "tester");
            request.setHeader("Connection", "close");
            request.setHeader("Accept-Encoding", "gzip");
            request.setURI("/context/file.txt");

            ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);

            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

            assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
            assertThat("Response[Vary]", response.get("Vary"), is("Accept-Encoding"));

            UncompressedMetadata metadata = parseResponseContent(response);
            assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is((int)Files.size(fileGood)));
            assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedGoodSha1Sum));
        }

        // Test Request 2
        {
            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod("GET");
            request.setVersion(HttpVersion.HTTP_1_1);
            request.setHeader("Host", "tester");
            request.setHeader("Connection", "close");
            request.setHeader("Accept-Encoding", "gzip");
            request.setURI("/context/bad.txt");

            ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

            HttpTester.Response response = HttpTester.parseResponse(rawResponse);

            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

            assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
            assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

            UncompressedMetadata metadata = parseResponseContent(response);
            int fileSize = (int)Files.size(fileBad);
            assertThat("Response Content Length", metadata.contentLength, is(fileSize));
            assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
            assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedBadSha1Sum));
        }
    }

    @Test
    public void testIsNotGzipCompressedSVGZ() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        Path testResource = MavenTestingUtils.getTestResourcePath("test.svgz");
        Path file = contextDir.resolve("test.svgz");
        IO.copy(testResource.toFile(), file.toFile());
        int fileSize = (int)Files.size(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/test.svgz");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Header checks
        assertThat("Response[Content-Type]", response.get("Content-Type"), containsString("image/svg+xml"));
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
    }

    @Test
    public void testUpperCaseMimeType() throws Exception
    {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.addExcludedMimeTypes("text/PLAIN");

        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        ServletHolder holder = new ServletHolder("default", DefaultServlet.class);
        holder.setInitParameter("etags", "true");
        servletContextHandler.addServlet(holder, "/");
        servletContextHandler.insertHandler(gzipHandler);

        server.setHandler(servletContextHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = createFile(contextDir, "file.txt", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.txt");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }
}
