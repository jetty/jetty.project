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

package org.eclipse.jetty.server.handler.gzip;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipHandlerTest
{
    protected static final int DEFAULT_OUTPUT_BUFFER_SIZE = new HttpConfiguration().getOutputBufferSize();

    private static final String CONTENT =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. " +
            "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque " +
            "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. " +
            "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam " +
            "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate " +
            "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. " +
            "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum " +
            "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa " +
            "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam " +
            "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. " +
            "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse " +
            "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    private static final byte[] CONTENT_BYTES = CONTENT.getBytes(StandardCharsets.UTF_8);

    // Content that is sized under the GzipHandler.minGzipSize default
    private static final String MICRO = CONTENT.substring(0, 10);

    private static final String CONTENT_ETAG = String.format("W/\"%x\"", CONTENT.hashCode());
    private static final String CONTENT_ETAG_GZIP = String.format("W/\"%x" + CompressedContentFormat.GZIP.getEtagSuffix() + "\"", CONTENT.hashCode());

    public WorkDir _workDir;
    private Server _server;
    private LocalConnector _connector;
    private GzipHandler _gzipHandler;
    private ContextHandler _contextHandler;

    public static Stream<Arguments> asyncResponseSource()
    {
        List<Arguments> args = new ArrayList<>();
        for (int writes : List.of(0, 1, 2, 32))
        {
            for (int bufferSize : List.of(0, 1, 16 * 1024, 128 * 1024))
            {
                for (boolean readOnly : List.of(true, false))
                {
                    for (boolean contentLength : List.of(true, false))
                    {
                        if (bufferSize > 16 * 1024 && writes > 2)
                            continue;
                        for (boolean knownLast : List.of(true, false))
                        {
                            args.add(Arguments.of(writes, bufferSize, readOnly, contentLength, knownLast));
                        }
                    }
                }
            }
        }
        return args.stream();
    }

    public static Stream<Integer> compressibleSizesSource()
    {
        return Stream.of(
            DEFAULT_OUTPUT_BUFFER_SIZE / 4,
            DEFAULT_OUTPUT_BUFFER_SIZE,
            DEFAULT_OUTPUT_BUFFER_SIZE * 4);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(_server);
    }

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);

        CheckHandler checkHandler = new CheckHandler();
        _server.setHandler(checkHandler);

        _gzipHandler = new GzipHandler();
        _gzipHandler.setMinGzipSize(16);
        _gzipHandler.setInflateBufferSize(4096);
        checkHandler.setHandler(_gzipHandler);

        _contextHandler = new ContextHandler("/ctx");
        _gzipHandler.setHandler(_contextHandler);
    }

    @Test
    public void testAddIncludePaths()
    {
        GzipHandler gzip = new GzipHandler();
        gzip.addIncludedPaths("/foo");
        gzip.addIncludedPaths("^/bar.*$");

        String[] includedPaths = gzip.getIncludedPaths();
        assertThat("Included Paths.size", includedPaths.length, is(2));
        assertThat("Included Paths", Arrays.asList(includedPaths), contains("/foo", "^/bar.*$"));
    }

    @ParameterizedTest
    @MethodSource("asyncResponseSource")
    public void testAsyncScenarios(int writes, int bufferSize, boolean readOnly, boolean contentLength, boolean knownLast) throws Exception
    {
        _contextHandler.setHandler(new WriteHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/async/info?writes=%d&bufferSize=%d&readOnly=%b&contentLength=%b&knownLast=%b"
            .formatted(writes, bufferSize, readOnly, contentLength, knownLast));
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));

        int expectedSize = writes * bufferSize;

        int actualWrites = writes + (knownLast ? 0 : 1);
        boolean gzipped = expectedSize >= GzipHandler.DEFAULT_MIN_GZIP_SIZE || !contentLength && actualWrites > 1;

        byte[] bytes;
        if (gzipped)
        {
            assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
            assertThat(response.getCSV("Vary", false), contains("Accept-Encoding"));

            ByteArrayInputStream rawContentStream = new ByteArrayInputStream(response.getContentBytes());
            InputStream testIn = new GZIPInputStream(rawContentStream);
            ByteArrayOutputStream testOut = new ByteArrayOutputStream();
            IO.copy(testIn, testOut);
            bytes = testOut.toByteArray();
        }
        else
        {
            bytes = response.getContentBytes();
        }

        byte[] expectedBuffer = new byte[bufferSize];
        int remaining = bufferSize;
        while (remaining > 0)
        {
            int len = Math.min(CONTENT_BYTES.length, remaining);
            System.arraycopy(CONTENT_BYTES, 0, expectedBuffer, bufferSize - remaining, len);
            remaining -= len;
        }

        byte[] expectedBytes = new byte[expectedSize];
        remaining = expectedSize;
        while (remaining > 0)
        {
            int len = Math.min(expectedBuffer.length, remaining);
            System.arraycopy(expectedBuffer, 0, expectedBytes, expectedSize - remaining, len);
            remaining -= len;
        }

        assertArrayEquals(expectedBytes, bytes);
    }

    @Test
    public void testBlockingResponse() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content?vary=Accept-Encoding,Other");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.get("ETag"), is(CONTENT_ETAG_GZIP));
        assertThat(response.getCSV("Vary", false), contains("Accept-Encoding", "Other"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(CONTENT, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testBufferResponse() throws Exception
    {
        _contextHandler.setHandler(new BufferHandler(CONTENT_BYTES));
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/buffer/info");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.getCSV("Vary", false), contains("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(CONTENT, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testDeleteETagGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("DELETE");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-Match", "WrongEtag" + CompressedContentFormat.GZIP.getEtagSuffix());
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), Matchers.is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));

        request = HttpTester.newRequest();
        request.setMethod("DELETE");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-Match", CONTENT_ETAG_GZIP);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), Matchers.is(HttpStatus.NO_CONTENT_204));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
    }

    @Test
    public void testETagGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-None-Match", CONTENT_ETAG_GZIP);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(304));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
        assertThat(response.get("ETag"), is(CONTENT_ETAG_GZIP));
    }

    @Test
    public void testETagNotGzipHandler() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("If-None-Match", CONTENT_ETAG);
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(304));
        assertThat(response.get("Content-Encoding"), not(Matchers.equalToIgnoringCase("gzip")));
        assertThat(response.get("ETag"), is(CONTENT_ETAG));
    }

    /**
     * Gzip when the produced response body content is zero bytes in length.
     */
    @Test
    public void testEmptyResponseDefaultMinGzipSize() throws Exception
    {
        _contextHandler.setHandler(new BufferHandler(new byte[0]));
        _server.start();

        // don't set minGzipSize, use default

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/empty");
        request.setVersion("HTTP/1.1");
        request.setHeader("Connection", "close");
        request.setHeader("Host", "tester");
        request.setHeader("Accept-Encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat(response.getCSV("Vary", false), contains("Accept-Encoding"));
    }

    @Test
    public void testEmptyResponseZeroMinGzipSize() throws Exception
    {
        _contextHandler.setHandler(new WriteHandler());
        _server.start();

        int writes = 0;
        _gzipHandler.setMinGzipSize(0);

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/async/info?writes=" + writes);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.getCSV("Vary", false), contains("Accept-Encoding"));
    }

    @Test
    public void testExcludeMimeTypes() throws Exception
    {
        // setting all excluded mime-types to a mimetype new mime-type
        // Note: this mime-type does not exist in MimeTypes object.
        _gzipHandler.setExcludedMimeTypes("image/webfoo");

        _contextHandler.setHandler(new MimeTypeContentHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        // Request something that is not present on MimeTypes and is also
        // excluded by GzipHandler configuration
        request.setMethod("GET");
        request.setURI("/ctx/mimetypes/foo.webfoo?type=image/webfoo");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Accept", "*/*");
        request.setHeader("Accept-Encoding", "gzip"); // allow compressed responses
        request.setHeader("Connection", "close");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat("Should not be compressed with gzip", response.get("Content-Encoding"), nullValue());
        assertThat(response.get("ETag"), nullValue());
        assertThat(response.get("Vary"), nullValue());

        // Request something that is present on MimeTypes and is also compressible
        // by the GzipHandler configuration
        request.setMethod("GET");
        request.setURI("/ctx/mimetypes/zed.txt");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Accept", "*/*");
        request.setHeader("Accept-Encoding", "gzip"); // allow compressed responses
        request.setHeader("Connection", "close");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), containsString("gzip"));
        assertThat(response.get("ETag"), nullValue());
        assertThat(response.get("Vary"), is("Accept-Encoding"));
    }

    @Test
    public void testExcludePaths() throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");
        _gzipHandler.setExcludedPaths("*.txt");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
    public void testExcludedMimeTypes() throws Exception
    {
        _gzipHandler.addExcludedMimeTypes("text/plain");

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        byte[] buffer = generateContent(fileSize);
        BufferHandler bufferHandler = new BufferHandler(buffer, "text/plain");
        String expectedSha1Sum = Sha1Sum.calculate(buffer);
        _contextHandler.setHandler(bufferHandler);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Type]", response.get("Content-Type"), containsString("text/plain"));
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testExcludedMimeTypesUpperCase() throws Exception
    {
        _gzipHandler.addExcludedMimeTypes("text/PLAIN");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
    public void testExcludedMimeTypesWithCharset() throws Exception
    {
        _gzipHandler.addExcludedMimeTypes("text/wibble");

        // Prepare Buffer
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        byte[] buffer = generateContent(fileSize);
        BufferHandler bufferHandler = new BufferHandler(buffer, "text/wibble; charset=utf-8");
        String expectedSha1Sum = Sha1Sum.calculate(buffer);
        _contextHandler.setHandler(bufferHandler);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/test_quotes.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Type]", response.get("Content-Type"), containsString("text/wibble"));
        assertThat("Response[Content-Type]", response.get("Content-Type"), containsString("charset=utf-8"));
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }

    @Test
    public void testGzipBomb() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        byte[] data = new byte[512 * 1024];
        Arrays.fill(data, (byte)'X');

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data);
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/echo");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(bytes);

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        // TODO need to test if back pressure works at some point too

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentBytes().length, is(512 * 1024));
    }

    @Test
    public void testGzipCompressedRequestForm() throws Exception
    {
        _contextHandler.setHandler(new DumpHandler());
        _server.start();

        String data = "name=value";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/dump");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(bytes);

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is("name: value\n"));
    }

    @Test
    public void testGzippedRequestPost() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        String data = "Hello Nice World! ";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/echo");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(bytes);

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is(data));
    }

    @Test
    public void testGzippedRequestPostChunked() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        String data = "Hello Nice World! ";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("POST");
        request.setURI("/ctx/echo");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.add("Transfer-Encoding", "chunked");
        request.setContent(bytes);
        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContent(), is(data));
    }

    @Test
    public void testIncludeExcludeInflationPaths() throws Exception
    {
        _contextHandler.setHandler(new EchoHandler());
        _server.start();

        _gzipHandler.addExcludedInflationPaths("/ctx/echo/exclude");
        _gzipHandler.addIncludedInflationPaths("/ctx/echo/include");

        String message = "hello world";
        byte[] gzippedMessage = gzipContent(message);

        // The included path does deflate the content.
        HttpTester.Response response = sendGzipRequest("/ctx/echo/include", message);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), equalTo(message));

        // The excluded path does not deflate the content.
        response = sendGzipRequest("/ctx/echo/exclude", message);
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContentBytes(), equalTo(gzippedMessage));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "WIBBLE", "GET", "HEAD"})
    public void testIncludeMethods(String method) throws Exception
    {
        _gzipHandler.setIncludedMethods("POST", "WIBBLE", "GET", "HEAD");

        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 8;
        byte[] buffer = generateContent(fileSize);
        _contextHandler.setHandler(new BufferHandler(buffer));
        String expectedSha1Sum = Sha1Sum.calculate(buffer);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod(method); // The point of this test
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), containsString("gzip"));
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

    @Test
    public void testIncludedExcludePaths() throws Exception
    {
        _gzipHandler.setExcludedPaths("/ctx/bad.txt");
        _gzipHandler.setIncludedPaths("*.txt");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        Path fileGood = Files.write(contextDir.resolve("file.txt"), generateContent(DEFAULT_OUTPUT_BUFFER_SIZE * 4));
        Path fileBad = Files.write(contextDir.resolve("bad.txt"), generateContent(DEFAULT_OUTPUT_BUFFER_SIZE * 2));
        String expectedGoodSha1Sum = Sha1Sum.calculate(fileGood);
        String expectedBadSha1Sum = Sha1Sum.calculate(fileBad);

        _server.start();

        // Test Request 1
        {
            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod("GET");
            request.setVersion(HttpVersion.HTTP_1_1);
            request.setHeader("Host", "tester");
            request.setHeader("Connection", "close");
            request.setHeader("Accept-Encoding", "gzip");
            request.setURI("/ctx/file.txt");

            ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
            request.setURI("/ctx/bad.txt");

            ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
    public void testIncludedMimeTypes() throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;
        Path file = Files.write(contextDir.resolve("file.mp3"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/file.mp3");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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

    /**
     * Test what happens if GzipHandler encounters content that is already compressed by
     * a handler that isn't GzipHandler.
     *
     * <p>
     * We use ResourceHandler and the precompressed content behaviors to simulate this
     * condition.
     * </p>
     */
    @Test
    public void testIncludedMimeTypesPrecompressedByWrappedHandler() throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("image/svg+xml");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        resourceHandler.setGzipEquivalentFileExtensions(List.of(".svgz"));
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File (a precompressed file)
        Path testResource = MavenPaths.findTestResourceFile("test.svgz");
        Path file = contextDir.resolve("test.svgz");
        Files.copy(testResource, file);
        int fileSize = (int)Files.size(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/test.svgz");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
    public void testLargeResponse() throws Exception
    {
        _contextHandler.setHandler(new WriteHandler());
        _server.start();

        int writes = 100;
        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/async/info?writes=" + writes);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), containsString("gzip"));
        assertThat(response.getCSV("Vary", false), contains("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        byte[] bytes = testOut.toByteArray();

        for (int i = 0; i < writes; i++)
        {
            assertEquals(CONTENT, new String(Arrays.copyOfRange(bytes, i * CONTENT_BYTES.length, (i + 1) * CONTENT_BYTES.length), StandardCharsets.UTF_8), "chunk " + i);
        }
    }

    @Test
    public void testMicroResponseChunkedNotCompressed() throws Exception
    {
        _contextHandler.setHandler(new MicroChunkedHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/microchunked");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "tester");
        request.setHeader("Accept-Encoding", "gzip");

        ByteBuffer rawresponse = _connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(rawresponse));
        response = HttpTester.parseResponse(rawresponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Transfer-Encoding"), containsString("chunked"));
        assertThat(response.get("Content-Encoding"), containsString("gzip"));
        assertThat(response.get("Vary"), is("Accept-Encoding"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(MICRO, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testMicroResponseNotCompressed() throws Exception
    {
        _contextHandler.setHandler(new MicroHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/micro");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Accept-Encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat(response.get("ETag"), is(CONTENT_ETAG));
        assertThat(response.get("Vary"), is("Accept-Encoding"));

        InputStream testIn = new ByteArrayInputStream(response.getContentBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(MICRO, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testRequestAcceptEncodingWithQuality() throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE / 4;
        Path file = Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "something; q=0.1, gzip; q=0.5"); // TESTING THIS
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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

    /**
     * Gzip incorrectly gzips when {@code Accept-Encoding: gzip; q=0}.
     *
     * <p>
     * A quality of 0 results in no compression.
     * </p>
     *
     * See: <a href="http://bugs.eclipse.org/388072">Bugzilla #388072</a>
     */
    @Test
    public void testRequestAcceptEncodingWithZeroQuality() throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE / 4;
        Path file = Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip; q=0"); // TESTING THIS
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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

    /**
     * Test of how the combination of a request with `If-Modified-Since` value in the future
     * arrives to a Handler under GzipHandler.  The ResourceHandler is used as it will
     * handle `If-Modified-Since` accordingly, and should return a 304 Not Modified response.
     * GzipHandler should not be compressing that response.
     */
    @ParameterizedTest
    @MethodSource("compressibleSizesSource")
    public void testRequestIfModifiedSinceInFutureGzipCompressedResponse(int fileSize) throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        Instant fourMinutesInFuture = Instant.now().plus(4, ChronoUnit.MINUTES);
        request.setHeader("If-Modified-Since", DateGenerator.formatDate(fourMinutesInFuture.toEpochMilli()));
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[ETag]", response.get("ETag"), nullValue());
        assertThat("Response[Vary]", response.get("Vary"), nullValue());
    }

    /**
     * Test of how the combination of a request with `If-Modified-Since` value in the past
     * arrives to a Handler under GzipHandler.  The ResourceHandler is used as it will
     * handle `If-Modified-Since` accordingly, and should return the requested resource.
     * GzipHandler should compress that response.
     */
    @ParameterizedTest
    @MethodSource("compressibleSizesSource")
    public void testRequestIfModifiedSinceInPastGzipCompressedResponse(int fileSize) throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        Path file = Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        Instant fourSecondsAgo = Instant.now().minusSeconds(4);
        request.setHeader("If-Modified-Since", DateGenerator.formatDate(fourSecondsAgo.toEpochMilli()));
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
    public void testRequestWithMultipleAcceptEncodingHeaders() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content?vary=Accept-Encoding,Other");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("accept-encoding", "deflate");
        request.setHeader("accept-encoding", "gzip");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), Matchers.equalToIgnoringCase("gzip"));
        assertThat(response.get("ETag"), is(CONTENT_ETAG_GZIP));
        assertThat(response.getCSV("Vary", false), contains("Accept-Encoding", "Other"));

        InputStream testIn = new GZIPInputStream(new ByteArrayInputStream(response.getContentBytes()));
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(CONTENT, testOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testResponseCustomMimeTypeSVG() throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("image/svg+xml");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        Path testResource = MavenPaths.findTestResourceFile("test.svg");
        Path file = contextDir.resolve("test.svg");
        Files.copy(testResource, file);
        String expectedSha1Sum = Sha1Sum.calculate(testResource);
        int fileSize = (int)Files.size(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/test.svg");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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
    public void testResponseNotCompressed() throws Exception
    {
        _contextHandler.setHandler(new TestHandler());
        _server.start();

        // generated and parsed test
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setURI("/ctx/content?vary=Other");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");

        response = HttpTester.parseResponse(_connector.getResponse(request.generate()));

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat(response.get("ETag"), is(CONTENT_ETAG));
        assertThat(response.getCSV("Vary", false), contains("Other", "Accept-Encoding"));

        InputStream testIn = new ByteArrayInputStream(response.getContentBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(CONTENT, testOut.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("compressibleSizesSource")
    public void testSimpleCompressedResponse(int fileSize) throws Exception
    {
        _gzipHandler.addIncludedMimeTypes("text/plain");

        Path contextDir = _workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        _contextHandler.setBaseResourceAsPath(contextDir);
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setEtags(true);
        _contextHandler.setHandler(resourceHandler);

        // Prepare Server File
        Path file = Files.write(contextDir.resolve("file.txt"), generateContent(fileSize));
        String expectedSha1Sum = Sha1Sum.calculate(file);

        _server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/ctx/file.txt");

        // Issue request
        ByteBuffer rawResponse = _connector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

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

    protected FilterInputStream newContentEncodingFilterInputStream(String contentEncoding, InputStream inputStream) throws IOException
    {
        if (contentEncoding == null)
        {
            return new FilterInputStream(inputStream) {};
        }
        else if (contentEncoding.contains(GzipHandler.GZIP))
        {
            return new GZIPInputStream(inputStream);
        }
        else if (contentEncoding.contains(GzipHandler.DEFLATE))
        {
            return new InflaterInputStream(inputStream, new Inflater(true));
        }
        throw new RuntimeException("Unexpected response content-encoding: " + contentEncoding);
    }

    protected UncompressedMetadata parseResponseContent(HttpTester.Response response) throws NoSuchAlgorithmException, IOException
    {
        UncompressedMetadata metadata = new UncompressedMetadata();
        metadata.contentLength = response.getContentBytes().length;

        String contentEncoding = response.get("Content-Encoding");
        MessageDigest digest = MessageDigest.getInstance("SHA1");

        try (ByteArrayInputStream bais = new ByteArrayInputStream(response.getContentBytes());
             FilterInputStream streamFilter = newContentEncodingFilterInputStream(contentEncoding, bais);
             ByteArrayOutputStream uncompressedStream = new ByteArrayOutputStream(metadata.contentLength);
             DigestOutputStream digester = new DigestOutputStream(uncompressedStream, digest))
        {
            org.eclipse.jetty.toolchain.test.IO.copy(streamFilter, digester);
            metadata.uncompressedContent = uncompressedStream.toByteArray();
            metadata.uncompressedSize = metadata.uncompressedContent.length;
            metadata.uncompressedSha1Sum = StringUtil.toHexString(digest.digest()).toUpperCase(Locale.ENGLISH);
            return metadata;
        }
    }

    /**
     * Generate semi-realistic text content of arbitrary length.
     * <p>
     * Note: We don't just create a single string of repeating characters
     * as that doesn't test the gzip behavior very well. (too efficient)
     * We also don't just generate a random byte array as that is the opposite
     * extreme of gzip handling (terribly inefficient).
     * </p>
     *
     * @param length the length of the content to generate.
     * @return the content.
     */
    private byte[] generateContent(int length)
    {
        StringBuilder builder = new StringBuilder();
        do
        {
            builder.append(CONTENT);
        }
        while (builder.length() < length);

        // Make sure we are exactly at requested length. (truncate the extra)
        if (builder.length() > length)
        {
            builder.setLength(length);
        }

        return builder.toString().getBytes(UTF_8);
    }

    private byte[] gzipContent(String content) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.close();
        return baos.toByteArray();
    }

    private HttpTester.Response sendGzipRequest(String uri, String data) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI(uri);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host", "tester");
        request.setHeader("Content-Type", "text/plain");
        request.setHeader("Content-Encoding", "gzip");
        request.setContent(gzipContent(data));

        return HttpTester.parseResponse(_connector.getResponse(request.generate()));
    }

    public static class MicroHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put("ETag", CONTENT_ETAG);
            String ifnm = request.getHeaders().get("If-None-Match");
            if (ifnm != null && ifnm.equals(CONTENT_ETAG))
                Response.writeError(request, response, callback, 304);
            else
            {
                Content.Sink.write(response, true, MICRO, callback);
            }
        }
    }

    public static class MicroChunkedHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            Content.Sink.write(response, false, MICRO, callback);
        }
    }

    public static class MimeTypeContentHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            String pathInfo = Request.getPathInContext(request);
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, getContentTypeFromRequest(pathInfo, request));
            Content.Sink.write(response, true, "This is content for " + pathInfo + "\n", callback);
        }

        private String getContentTypeFromRequest(String filename, Request request)
        {
            String defaultContentType = "application/octet-stream";
            Fields parameters = Request.extractQueryParameters(request);
            if (parameters.get("type") != null)
                defaultContentType = parameters.get("type").getValue();

            Context context = request.getContext();
            String contentType = context.getMimeTypes().getMimeByExtension(filename);
            if (contentType != null)
                return contentType;
            return defaultContentType;
        }
    }

    public static class TestHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            if (HttpMethod.DELETE.is(request.getMethod()))
            {
                doDelete(request, response, callback);
                return;
            }

            Fields parameters = Request.extractQueryParameters(request);
            if (parameters.get("vary") != null)
                response.getHeaders().add("Vary", parameters.get("vary").getValue());
            response.getHeaders().put("ETag", CONTENT_ETAG);
            String ifnm = request.getHeaders().get("If-None-Match");
            if (ifnm != null && ifnm.equals(CONTENT_ETAG))
            {
                Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
                return;
            }

            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");
            Content.Sink.write(response, true, CONTENT, callback);
        }

        void doDelete(Request request, Response response, Callback callback) throws IOException
        {
            String ifm = request.getHeaders().get("If-Match");
            if (ifm != null && ifm.equals(CONTENT_ETAG))
                Response.writeError(request, response, callback, HttpStatus.NO_CONTENT_204);
            else
                Response.writeError(request, response, callback, HttpStatus.NOT_MODIFIED_304);
        }
    }

    public static class WriteHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            Fields parameters = Request.extractQueryParameters(request);

            byte[] bytes;
            String size = parameters.getValue("bufferSize");
            if (size == null)
                bytes = CONTENT_BYTES;
            else
            {
                int s = Integer.parseInt(size);
                bytes = new byte[s];
                while (s > 0)
                {
                    int l = Math.min(CONTENT_BYTES.length, s);
                    System.arraycopy(CONTENT_BYTES, 0, bytes, bytes.length - s, l);
                    s = s - l;
                }
            }

            String writes = parameters.getValue("writes");
            AtomicInteger count = new AtomicInteger(writes == null ? 1 : Integer.parseInt(writes));

            boolean ro = Boolean.parseBoolean(parameters.getValue("readOnly"));
            boolean cl = Boolean.parseBoolean(parameters.getValue("contentLength"));
            boolean knownLast = Boolean.parseBoolean(parameters.getValue("knownLast"));

            if (cl)
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, (long)count.get() * bytes.length);

            Runnable writer = new Runnable()
            {
                @Override
                public void run()
                {
                    int c = count.getAndDecrement();

                    boolean last = c == 0 || c == 1 && knownLast;
                    Callback cb = last ? callback : Callback.from(this);

                    ByteBuffer buffer = null;
                    if (c > 0)
                    {
                        buffer = ByteBuffer.wrap(bytes);
                        if (ro)
                            buffer = buffer.asReadOnlyBuffer();
                    }

                    response.write(last, buffer, cb);
                }
            };

            Context context = request.getContext();
            context.run(writer);
        }
    }

    public static class BufferHandler extends Handler.Processor
    {
        private final ByteBuffer byteBuffer;
        private final String contentType;

        public BufferHandler(byte[] bytes)
        {
            this(bytes, "text/plain");
        }

        public BufferHandler(byte[] bytes, String contentType)
        {
            this.byteBuffer = BufferUtil.toBuffer(bytes).asReadOnlyBuffer();
            this.contentType = contentType;
        }

        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, byteBuffer.remaining());
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, this.contentType);
            response.write(true, byteBuffer, callback);
        }
    }

    public static class EchoHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            HttpField contentType = request.getHeaders().getField(HttpHeader.CONTENT_TYPE);
            if (contentType != null)
                response.getHeaders().add(contentType);

            Content.copy(request, response, callback);
        }
    }

    public static class DumpHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

            Fields parameters = Request.extractQueryParameters(request);
            FormFields futureFormFields = new FormFields(request, StandardCharsets.UTF_8, -1, -1, parameters);
            futureFormFields.run();
            parameters = futureFormFields.get();

            String dump = parameters.stream().map(f -> "%s: %s\n".formatted(f.getName(), f.getValue())).collect(Collectors.joining());
            Content.Sink.write(response, true, dump, callback);
        }
    }

    public static class UncompressedMetadata
    {
        public byte[] uncompressedContent;
        public int contentLength;
        public String uncompressedSha1Sum;
        public int uncompressedSize;
    }

    public static class CheckHandler extends Handler.Wrapper
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            if (request.getHeaders().get("X-Content-Encoding") != null)
                assertEquals(-1, request.getLength());
            else if (request.getLength() >= 0)
                MatcherAssert.assertThat(request.getHeaders().get("X-Content-Encoding"), nullValue());
            super.process(request, response, callback);

        }
    }
}
