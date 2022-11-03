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

package org.eclipse.jetty.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jetty.http.CachingHttpContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.EtagUtils;
import org.eclipse.jetty.http.FileMappedHttpContentFactory;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.PreCompressedHttpContentFactory;
import org.eclipse.jetty.http.ResourceHttpContentFactory;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.http.ValidatingCachingContentFactory;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_ENCODING;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpHeader.ETAG;
import static org.eclipse.jetty.http.HttpHeader.LAST_MODIFIED;
import static org.eclipse.jetty.http.HttpHeader.LOCATION;
import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeader;
import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeaderValue;
import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.headerValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Resource Handler test
 */
@ExtendWith(WorkDirExtension.class)
public class ResourceHandlerTest
{
    public WorkDir workDir;

    private Path docRoot;

    private Server _server;
    private LocalConnector _local;

    private ResourceHandler _rootResourceHandler;

    private ContextHandlerCollection _contextHandlerCollection;

    private static void addBasicWelcomeScenarios(Scenarios scenarios)
    {
        scenarios.addScenario(
            "GET /context/one/ (index.htm match)",
            """
                GET /context/one/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Hello Inde</h1>"))
        );

        scenarios.addScenario(
            "GET /context/two/ (index.html match)",
            """
                GET /context/two/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"))
        );

        scenarios.addScenario(
            "GET /context/three/ (index.html wins over index.htm)",
            """
                GET /context/three/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Three Index</h1>"))
        );
    }

    /**
     * Attempt to create the directory, skip testcase if not supported on OS.
     */
    private static Path assumeMkDirSupported(Path path, String subpath)
    {
        Path ret = null;

        try
        {
            ret = path.resolve(subpath);

            if (Files.exists(ret))
                return ret;

            Files.createDirectories(ret);
        }
        catch (InvalidPathException | IOException ignore)
        {
            // ignore
        }

        assumeTrue(ret != null, "Directory creation not supported on OS: " + path + File.separator + subpath);
        assumeTrue(Files.exists(ret), "Directory creation not supported on OS: " + ret);

        return ret;
    }

    @SuppressWarnings("Duplicates")
    public static Stream<Arguments> contextBreakoutScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario("""
                GET /context/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"))
        );

        scenarios.addScenario("""
                GET /context/index.html HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response.getContent(), containsString("Hello Index"))
        );

        List<String> notEncodedPrefixes = new ArrayList<>();
        notEncodedPrefixes.add("/context/dir;");

        for (String prefix : notEncodedPrefixes)
        {
            scenarios.addScenario("""
                    GET @PREFIX@ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.NOT_FOUND_404
            );

            scenarios.addScenario("""
                    GET @PREFIX@/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX", prefix),
                HttpStatus.NOT_FOUND_404
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../../sekret/pass HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX", prefix),
                HttpStatus.NOT_FOUND_404,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/..;/..;/sekret/pass HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.BAD_REQUEST_400,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/%2E%2E/%2E%2E/sekret/pass HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.BAD_REQUEST_400,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../index.html HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.NOT_FOUND_404,
                (response) -> assertThat(response.getContent(), not(containsString("Hello Index")))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/%2E%2E/index.html HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.BAD_REQUEST_400
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../../ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.NOT_FOUND_404,
                (response) ->
                {
                    String body = response.getContent();
                    assertThat(body, not(containsString("Directory: ")));
                }
            );
        }

        List<String> encodedPrefixes = new ArrayList<>();

        if (!OS.WINDOWS.isCurrentOs())
        {
            encodedPrefixes.add("/context/dir%3F");
        }
        encodedPrefixes.add("/context/dir%3B");

        for (String prefix : encodedPrefixes)
        {
            scenarios.addScenario("""
                    GET @PREFIX@ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.MOVED_TEMPORARILY_302,
                (response) -> assertThat("Location header", response.get(HttpHeader.LOCATION), endsWith(prefix + "/"))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.OK_200
            );

            scenarios.addScenario("""
                    GET @PREFIX@/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.OK_200,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../index.html HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.OK_200,
                (response) -> assertThat(response.getContent(), containsString("Hello Index"))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../../ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.NOT_FOUND_404,
                (response) ->
                {
                    String body = response.getContent();
                    assertThat(body, containsString("/../../"));
                    assertThat(body, not(containsString("Directory: ")));
                }
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../../sekret/pass HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.NOT_FOUND_404,
                (response) -> assertThat(response.getContent(), not(containsString("Sssh")))
            );

            scenarios.addScenario("""
                    GET @PREFIX@/../index.html HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """.replace("@PREFIX@", prefix),
                HttpStatus.OK_200,
                (response) -> assertThat(response.getContent(), containsString("Hello Index"))
            );
        }

        return scenarios.stream();
    }

    private static String getContentTypeBoundary(HttpField contentType)
    {
        Pattern pat = Pattern.compile("boundary=([a-zA-Z0-9]*)");
        for (String value : contentType.getValues())
        {
            Matcher mat = pat.matcher(value);
            if (mat.find())
                return mat.group(1);
        }

        return null;
    }

    public static Stream<Arguments> rangeScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario(
            "No range requested",
            """
                GET /context/data.txt HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response, containsHeaderValue(HttpHeader.ACCEPT_RANGES, "bytes"))
        );

        scenarios.addScenario(
            "Simple range request (no-close)",
            """
                GET /context/data.txt HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                assertThat(response, containsHeaderValue("Content-Type", "text/plain"));
                assertThat(response, containsHeaderValue("Content-Length", "10"));
                assertThat(response, containsHeaderValue("Content-Range", "bytes 0-9/80"));
            }
        );

        scenarios.addScenario(
            "Simple range request w/close",
            """
                GET /context/data.txt HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9\r
                Connection: close\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                assertThat(response, containsHeaderValue("Content-Type", "text/plain"));
                assertThat(response, containsHeaderValue("Content-Range", "bytes 0-9/80"));
            });

        scenarios.addScenario(
            "Multiple ranges (x3)",
            """
                GET /context/data.txt HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9,20-29,40-49\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        scenarios.addScenario("Multiple ranges (x4)",
            """
                GET /context/data.txt HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9,20-29,40-49,70-79\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));
                assertThat(body, containsString("Content-Range: bytes 70-79/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        scenarios.addScenario(
            "Multiple ranges (x4) with empty range request",
            """
                GET /context/data.txt HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9,20-29,40-49,60-60,70-79\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));
                assertThat(body, containsString("Content-Range: bytes 60-60/80")); // empty range request
                assertThat(body, containsString("Content-Range: bytes 70-79/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        // test a range request with a file with no suffix, therefore no mimetype

        scenarios.addScenario(
            "No mimetype resource - no range requested",
            """
                GET /context/nofilesuffix HTTP/1.1\r
                Host: localhost\r
                \r
                """,
            HttpStatus.OK_200,
            (response) -> assertThat(response, containsHeaderValue(HttpHeader.ACCEPT_RANGES, "bytes"))
        );

        scenarios.addScenario(
            "No mimetype resource - simple range request",
            """
                GET /context/nofilesuffix HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "10"));
                assertThat(response, containsHeaderValue(HttpHeader.CONTENT_RANGE, "bytes 0-9/80"));
                assertThat(response, not(containsHeader(HttpHeader.CONTENT_TYPE)));
            }
        );

        scenarios.addScenario(
            "No mimetype resource - multiple ranges (x3)",
            """
                GET /context/nofilesuffix HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9,20-29,40-49\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        scenarios.addScenario(
            "No mimetype resource - multiple ranges (x5) with empty range request",
            """
                GET /context/nofilesuffix HTTP/1.1\r
                Host: localhost\r
                Range: bytes=0-9,20-29,40-49,60-60,70-79\r
                \r
                """,
            HttpStatus.PARTIAL_CONTENT_206,
            (response) ->
            {
                String body = response.getContent();

                assertThat(response, containsHeaderValue("Content-Type", "multipart/byteranges"));
                assertThat(response, containsHeaderValue("Content-Length", "" + body.length()));

                HttpField contentType = response.getField(HttpHeader.CONTENT_TYPE);
                String boundary = getContentTypeBoundary(contentType);

                assertThat("Boundary expected: " + contentType.getValue(), boundary, notNullValue());

                assertThat(body, containsString("Content-Range: bytes 0-9/80"));
                assertThat(body, containsString("Content-Range: bytes 20-29/80"));
                assertThat(body, containsString("Content-Range: bytes 40-49/80"));
                assertThat(body, containsString("Content-Range: bytes 60-60/80")); // empty range
                assertThat(body, containsString("Content-Range: bytes 70-79/80"));

                assertThat(response.getContent(), startsWith("--" + boundary));
                assertThat(response.getContent(), endsWith(boundary + "--\r\n"));
            }
        );

        return scenarios.stream();
    }

    public static Stream<Arguments> welcomeScenarios()
    {
        Scenarios scenarios = new Scenarios();

        scenarios.addScenario(
            "GET /context/ - (no match)",
            """
                GET /context/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """,
            HttpStatus.FORBIDDEN_403
        );

        addBasicWelcomeScenarios(scenarios);

        return scenarios.stream();
    }

    @BeforeEach
    public void before() throws Exception
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        docRoot = workDir.getEmptyPathDir().resolve("docroot");
        FS.ensureEmpty(docRoot);

        _server = new Server();
        _local = new LocalConnector(_server);
        _local.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _server.addConnector(_local);

        _rootResourceHandler = new ResourceHandler()
        {
            @Override
            protected HttpContent.Factory setupContentFactory()
            {
                // For testing the cache should be configured to validate the entry on every request.
                HttpContent.Factory contentFactory = new ResourceHttpContentFactory(ResourceFactory.of(getBaseResource()), getMimeTypes());
                contentFactory = new PreCompressedHttpContentFactory(contentFactory, getPrecompressedFormats());
                contentFactory = new FileMappedHttpContentFactory(contentFactory);
                contentFactory = new ValidatingCachingContentFactory(contentFactory, 0, _local.getByteBufferPool());
                return contentFactory;
            }
        };
        _rootResourceHandler.setWelcomeFiles("welcome.txt");
        _rootResourceHandler.setRedirectWelcome(false);

        ContextHandler contextHandler = new ContextHandler("/context");
        contextHandler.setHandler(_rootResourceHandler);
        contextHandler.setBaseResource(ResourceFactory.root().newResource(docRoot));

        _contextHandlerCollection = new ContextHandlerCollection();
        _contextHandlerCollection.addHandler(contextHandler);

        _server.setHandler(_contextHandlerCollection);
        _server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        _server.setHandler((Handler)null);
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testBigFile() throws Exception
    {
        copyBigText(docRoot);
        getLocalConnectorConfig().setOutputBufferSize(2048);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
    }

    @Test
    public void testBigFileBigBuffer() throws Exception
    {
        copyBigText(docRoot);
        getLocalConnectorConfig().setOutputBufferSize(16 * 1024);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
    }

    @Test
    public void testBigFileLittleBuffer() throws Exception
    {
        copyBigText(docRoot);
        getLocalConnectorConfig().setOutputBufferSize(8);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
    }

    @Test
    public void testBigger() throws Exception
    {
        setupBigFiles(docRoot);
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/bigger.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("   400\tThis is a big file\n     1\tThis is a big file"));
        assertThat(response.getContent(), containsString("   400\tThis is a big file\n"));
    }

    @Test
    public void testBrotliInitialCompressed() throws Exception
    {
        Files.writeString(docRoot.resolve("data0.txt"), "Hello Text 0", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.br"), "fake brotli", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.BR);
        _rootResourceHandler.setEtags(true);

        String rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip;q=0.9,br\r
            \r
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        String body = response.getContent();
        assertThat(body, containsString("fake brotli"));
    }

    @Test
    public void testBrotliWithEtags() throws Exception
    {
        Files.writeString(docRoot.resolve("data0.txt"), "Hello Text 0", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.br"), "fake brotli", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.BR);
        _rootResourceHandler.setEtags(true);

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));

        String etag = response.get(HttpHeader.ETAG);
        String etagBr = EtagUtils.rewriteWithSuffix(etag, "--br");

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip;q=0.9,br\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));
        body = response.getContent();
        assertThat(body, containsString("fake br"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt.br HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br,gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/brotli"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain br variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagBr)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake br"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt.br HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: W/"wobble"\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/brotli"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain br variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagBr)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake br"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etagBr));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etagBr));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    public void testCachedBrotli() throws Exception
    {
        Files.writeString(docRoot.resolve("data0.txt"), "Hello Text 0", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.br"), "fake brotli", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.BR);
        _rootResourceHandler.setEtags(true);

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));

        String etag = response.get(HttpHeader.ETAG);
        String etagBr = EtagUtils.rewriteWithSuffix(etag, "--br");

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));
        body = response.getContent();
        assertThat(body, containsString("fake brotli"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt.br HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "11"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/brotli"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain br variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagBr)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake brotli"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etagBr));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etagBr));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagBr));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: br\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    public void testCachedGzip() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path file0 = docRoot.resolve("data0.txt");
        Files.writeString(file0, "Hello Text 0", UTF_8);
        Path file0gz = docRoot.resolve("data0.txt.gz");
        Files.writeString(file0gz, "fake gzip", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);
        _rootResourceHandler.setEtags(true);

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Connection: close\r
            Host: localhost:8080\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));

        String etag = response.get(HttpHeader.ETAG);
        String etagGzip = EtagUtils.rewriteWithSuffix(etag, "--gzip");

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Connection: close\r
            Host: localhost:8080\r
            Accept-Encoding: gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt.gz HTTP/1.1\r
            Connection: close\r
            Host: localhost:8080\r
            Accept-Encoding: gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/gzip"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain gzip variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagGzip)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etagGzip));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etagGzip));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    public void testCachingDirectoryNotCached() throws Exception
    {
        copySimpleTestResource(docRoot);
        // TODO explicitly turn on caching
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();
        _rootResourceHandler.setWelcomeFiles(List.of()); // disable welcome files otherwise they get cached

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/directory/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Directory: /context/directory/"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingFilesCached() throws Exception
    {
        copySimpleTestResource(docRoot);
        // TODO explicitly turn on caching
        long expectedSize = Files.size(docRoot.resolve("big.txt"));
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        contentFactory.flushCache();
        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingMaxCacheSizeRespected() throws Exception
    {
        copySimpleTestResource(docRoot);
        long expectedSize = Files.size(docRoot.resolve("simple.txt"));
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();
        contentFactory.setMaxCacheSize((int)expectedSize);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/simple.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), equalTo("simple text"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));
    }

    @Test
    public void testCachingMaxCachedFileSizeRespected() throws Exception
    {
        copySimpleTestResource(docRoot);
        // TODO explicitly turn on caching
        long expectedSize = Files.size(docRoot.resolve("simple.txt"));
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();
        contentFactory.setMaxCachedFileSize((int)expectedSize);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/simple.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), equalTo("simple text"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));
    }

    @Test
    public void testCachingMaxCachedFilesRespected() throws Exception
    {
        copySimpleTestResource(docRoot);
        long expectedSizeBig = Files.size(docRoot.resolve("big.txt"));
        long expectedSizeSimple = Files.size(docRoot.resolve("simple.txt"));
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();
        contentFactory.setMaxCachedFiles(1);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSizeBig));

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/simple.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), equalTo("simple text"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(oneOf(expectedSizeBig, expectedSizeSimple)));
    }

    @Test
    public void testCachingNotFoundNotCached() throws Exception
    {
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/does-not-exist HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
            assertThat(response.getContent(), containsString("Error 404 Not Found"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingPrecompressedFilesCached() throws Exception
    {
        setupBigFiles(docRoot);

        long expectedSize = Files.size(docRoot.resolve("big.txt")) +
            Files.size(docRoot.resolve("big.txt.gz"));

        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response1 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: gzip\r
                    \r
                    """));
            assertThat(response1.getStatus(), is(HttpStatus.OK_200));
            assertThat(response1.get(CONTENT_ENCODING), is("gzip"));
            // Load big.txt.gz into a byte array and assert its contents byte per byte.
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
            {
                Files.copy(docRoot.resolve("big.txt.gz"), baos);
                assertThat(response1.getContentBytes(), is(baos.toByteArray()));
            }

            HttpTester.Response response2 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: deflate\r
                    \r
                    """));
            assertThat(response2.getStatus(), is(HttpStatus.OK_200));
            assertThat(response2.get(CONTENT_ENCODING), is(nullValue()));
            assertThat(response2.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response2.getContent(), endsWith("   400\tThis is a big file\n"));
        }

        assertThat(contentFactory.getCachedFiles(), is(2));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        contentFactory.flushCache();
        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingPrecompressedFilesCachedEtagged() throws Exception
    {
        setupBigFiles(docRoot);
        long expectedSize = Files.size(docRoot.resolve("big.txt")) +
            Files.size(docRoot.resolve("big.txt.gz"));

        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);
        _rootResourceHandler.setEtags(true);
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response1 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: gzip\r
                    \r
                    """));
            assertThat(response1.getStatus(), is(HttpStatus.OK_200));
            assertThat(response1.get(CONTENT_ENCODING), is("gzip"));
            String eTag1 = response1.get(ETAG);
            assertThat(eTag1, endsWith("--gzip\""));
            assertThat(eTag1, startsWith("W/"));
            String nakedEtag1 = QuotedStringTokenizer.unquote(eTag1.substring(2));
            // Load big.txt.gz into a byte array and assert its contents byte per byte.
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
            {
                Files.copy(docRoot.resolve("big.txt.gz"), baos);
                assertThat(response1.getContentBytes(), is(baos.toByteArray()));
            }

            HttpTester.Response response2 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: deflate\r
                    \r
                    """));
            assertThat(response2.getStatus(), is(HttpStatus.OK_200));
            assertThat(response2.get(CONTENT_ENCODING), is(nullValue()));
            String eTag2 = response2.get(ETAG);
            assertThat(eTag2, startsWith("W/"));
            String nakedEtag2 = QuotedStringTokenizer.unquote(eTag2.substring(2));
            assertThat(nakedEtag1, startsWith(nakedEtag2));
            assertThat(response2.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response2.getContent(), endsWith("   400\tThis is a big file\n"));

            HttpTester.Response response3 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: gzip\r
                    If-None-Match: %s \r
                    \r
                    """.formatted(eTag1)));
            assertThat(response3.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

            HttpTester.Response response4 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: deflate\r
                    If-None-Match: %s\r
                    \r
                    """.formatted(eTag2)));
            assertThat(response4.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        }

        assertThat(contentFactory.getCachedFiles(), is(2));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        contentFactory.flushCache();
        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingRefreshing() throws Exception
    {
        Path tempPath = docRoot.resolve("temp.txt");
        Files.writeString(tempPath, "temp file");
        long expectedSize = Files.size(tempPath);

        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/temp.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), equalTo("temp file"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        // re-write the file as long as its last modified timestamp did not change
        FileTime before = Files.getLastModifiedTime(tempPath);
        do
        {
            Files.writeString(tempPath, "updated temp file");
        }
        while (Files.getLastModifiedTime(tempPath).equals(before));
        long newExpectedSize = Files.size(tempPath);

        // The cached Resource will only go to fileSystem for expiryTime once per second.
        Thread.sleep(1100);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/temp.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), equalTo("updated temp file"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(newExpectedSize));

        Files.deleteIfExists(tempPath);
    }

    @Test
    public void testCachingWelcomeFileCached() throws Exception
    {
        copySimpleTestResource(docRoot);
        long expectedSize = Files.size(docRoot.resolve("directory/welcome.txt"));
        CachingHttpContentFactory contentFactory = (CachingHttpContentFactory)_rootResourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /context/directory/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Hello"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));
    }

    @Test
    public void testConditionalGetResponseCommitted() throws Exception
    {
        copyBigText(docRoot);
        getLocalConnectorConfig().setOutputBufferSize(8);
        _rootResourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Match: "NO_MATCH"\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testConditionalHeadResponseCommitted() throws Exception
    {
        copyBigText(docRoot);
        getLocalConnectorConfig().setOutputBufferSize(8);
        _rootResourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                HEAD /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Match: "NO_MATCH"\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testControlCharacter() throws Exception
    {
        FS.ensureDirExists(docRoot);

        try (StacklessLogging ignore = new StacklessLogging(ResourceService.class))
        {
            String rawResponse = _local.getResponse("""
                GET /context/%0a HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), anyOf(is(HttpStatus.NOT_FOUND_404), is(HttpStatus.INTERNAL_SERVER_ERROR_500)));
            assertThat("Response.content", response.getContent(), is(not(containsString(docRoot.toString()))));
        }
    }

    @Test
    public void testCustomCompressionFormats() throws Exception
    {
        Files.writeString(docRoot.resolve("data0.txt"), "Hello Text 0", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.br"), "fake brotli", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.gz"), "fake gzip", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.bz2"), "fake bzip2", UTF_8);

        _rootResourceHandler.setPrecompressedFormats(
            new CompressedContentFormat("bzip2", ".bz2"),
            new CompressedContentFormat("gzip", ".gz"),
            new CompressedContentFormat("br", ".br")
        );

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: bzip2, br, gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "10"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "bzip2"));
        body = response.getContent();
        assertThat(body, containsString("fake bzip2"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Accept-Encoding: br, gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));
    }

    @Test
    public void testDefaultBrotliOverGzip() throws Exception
    {
        Path textFile = docRoot.resolve("data0.txt");
        Path textBrFile = docRoot.resolve("data0.txt.br");
        Path textGzipFile = docRoot.resolve("data0.txt.gz");
        Files.writeString(textFile, "Hello Text 0", UTF_8);
        Files.writeString(textBrFile, "fake brotli", UTF_8);
        Files.writeString(textGzipFile, "fake gzip", UTF_8);

        // This tests the ResourceService Preferred Encoding Order configuration
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.BR, CompressedContentFormat.GZIP);

        String rawResponse;
        HttpTester.Response response;
        String body;

        // Request Ordered [gzip, compress, br] - should favor [br] due to ResourceService preferred encoding order
        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip, compress, br\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.toString(), response.getField(HttpHeader.CONTENT_LENGTH).getLongValue(), is(Files.size(textBrFile)));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "br"));
        body = response.getContent();
        assertThat(body, containsString("fake brotli"));

        // Request weighted [br] lower than defaults of [gzip, compress] - should favor [gzip] due to weighting
        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip, compress, br;q=0.9\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.toString(), response.getField(HttpHeader.CONTENT_LENGTH).getLongValue(), is(Files.size(textGzipFile)));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));
    }

    public static Stream<Arguments> directoryRedirectSource()
    {
        return Stream.of(
            Arguments.of("""
                GET /context/directory HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """, "/context/directory/"),
            Arguments.of("""
                GET /context/directory;JSESSIONID=12345678 HTTP/1.1\r
                Host: local\r
                Connection: close\r              
                \r
                """, "/context/directory/;JSESSIONID=12345678"),
            Arguments.of("""
                GET /context/directory?name=value HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """, "/context/directory/?name=value"),
            Arguments.of("""
                GET /context/directory;JSESSIONID=12345678?name=value HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """, "/context/directory/;JSESSIONID=12345678?name=value")
        );
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("directoryRedirectSource")
    public void testDirectoryRedirect(String rawRequest, String expectedLocationEndsWith) throws Exception
    {
        copySimpleTestResource(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(_local.getResponse(rawRequest));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith(expectedLocationEndsWith));
    }

    @Test
    public void testDirectory() throws Exception
    {
        copySimpleTestResource(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContent();
        assertThat(content, containsString("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />"));
        assertThat(content, containsString("Directory: /context"));
        assertThat(content, containsString("/context/big.txt")); // TODO should these be relative links?
        assertThat(content, containsString("/context/simple.txt"));
        assertThat(content, containsString("/context/directory/"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/jetty-dir.css HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testDirectoryOfCollection() throws Exception
    {
        copySimpleTestResource(docRoot);
        _rootResourceHandler.stop();
        _rootResourceHandler.setBaseResource(ResourceFactory.combine(
            ResourceFactory.root().newResource(MavenPaths.findTestResourceDir("layer0")),
            _rootResourceHandler.getBaseResource()));
        _rootResourceHandler.start();

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/other/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContent();
        assertThat(content, containsString("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />"));
        assertThat(content, containsString("Directory: /context/other"));
        assertThat(content, containsString("/context/other/data.txt"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/other/jetty-dir.css HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/double/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContent();
        assertThat(content, containsString("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />"));
        assertThat(content, containsString("Directory: /context/double"));
        assertThat(content, containsString("/context/double/zero.txt"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/double/jetty-dir.css HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testDirectoryOfCollections() throws Exception
    {
        copySimpleTestResource(docRoot);
        _rootResourceHandler.stop();
        _rootResourceHandler.setBaseResource(ResourceFactory.combine(
            ResourceFactory.root().newResource(MavenPaths.findTestResourceDir("layer0")),
            ResourceFactory.root().newResource(MavenPaths.findTestResourceDir("layer1")),
            _rootResourceHandler.getBaseResource()));
        _rootResourceHandler.start();

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String content = response.getContent();
        assertThat(content, containsString("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />"));
        assertThat(content, containsString("Directory: /context"));
        assertThat(content, containsString("/context/big.txt")); // TODO should these be relative links?
        assertThat(content, containsString("/context/simple.txt"));
        assertThat(content, containsString("/context/directory/"));
        assertThat(content, containsString("/context/other/"));
        assertThat(content, containsString("/context/double/"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/double/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        content = response.getContent();
        assertThat(content, containsString("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />"));
        assertThat(content, containsString("Directory: /context/double"));
        assertThat(content, containsString("/context/double/zero.txt"));
        assertThat(content, containsString("/context/double/one.txt"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/double/jetty-dir.css HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testEtagIfNoneMatchModifiedFile() throws Exception
    {
        _rootResourceHandler.setEtags(true);
        Path testFile = docRoot.resolve("test-etag-file.txt");
        Files.writeString(testFile, "some content\n");

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/test-etag-file.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), equalTo("some content\n"));
        assertThat(response.get(ETAG), notNullValue());
        String etag = response.get(ETAG);

        // re-write the file as long as its last modified timestamp did not change
        FileTime before = Files.getLastModifiedTime(testFile);
        do
        {
            Files.writeString(testFile, "some different content\n");
        }
        while (Files.getLastModifiedTime(testFile).equals(before));

        // The cached Resource will only go to fileSystem for expiryTime once per second.
        Thread.sleep(1100);

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/test-etag-file.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-None-Match: %s\r
                \r
                """.formatted(etag)));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), equalTo("some different content\n"));
    }

    @Test
    public void testEtagIfNoneMatchNotModifiedFile() throws Exception
    {
        copyBigText(docRoot);
        _rootResourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(ETAG), notNullValue());
        String etag = response.get(ETAG);

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-None-Match: %s\r
                \r
                """.formatted(etag)));
        assertThat(response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response.getContent(), is(""));
    }

    @Test
    public void testGet() throws Exception
    {
        Path file = docRoot.resolve("file.txt");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        Files.writeString(file, "How now brown cow", UTF_8);

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.toString(), response.getContent(), is("How now brown cow"));
    }

    @Test
    public void testGetUtf8NfcFile() throws Exception
    {
        FS.ensureEmpty(docRoot);

        // Create file with UTF-8 NFC format
        String filename = "swedish-" + new String(StringUtil.fromHexString("C3A5"), UTF_8) + ".txt";
        Files.writeString(docRoot.resolve(filename), "hi a-with-circle", UTF_8);

        // Using filesystem, attempt to access via NFD format
        Path nfdPath = docRoot.resolve("swedish-a" + new String(StringUtil.fromHexString("CC8A"), UTF_8) + ".txt");
        boolean filesystemSupportsNFDAccess = Files.exists(nfdPath);

        // Make requests
        String rawResponse;
        HttpTester.Response response;

        // Request as UTF-8 NFC
        rawResponse = _local.getResponse("""
            GET /context/swedish-%C3%A5.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("hi a-with-circle"));

        // Request as UTF-8 NFD
        rawResponse = _local.getResponse("""
            GET /context/swedish-a%CC%8A.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        if (filesystemSupportsNFDAccess)
        {
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("hi a-with-circle"));
        }
        else
        {
            assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        }
    }

    @Test
    public void testGetUtf8NfdFile() throws Exception
    {
        FS.ensureEmpty(docRoot);

        // Create file with UTF-8 NFD format
        String filename = "swedish-a" + new String(StringUtil.fromHexString("CC8A"), UTF_8) + ".txt";
        Files.writeString(docRoot.resolve(filename), "hi a-with-circle", UTF_8);

        // Using filesystem, attempt to access via NFC format
        Path nfcPath = docRoot.resolve("swedish-" + new String(StringUtil.fromHexString("C3A5"), UTF_8) + ".txt");
        boolean filesystemSupportsNFCAccess = Files.exists(nfcPath);

        String rawResponse;
        HttpTester.Response response;

        // Request as UTF-8 NFD
        rawResponse = _local.getResponse("""
            GET /context/swedish-a%CC%8A.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), is("hi a-with-circle"));

        // Request as UTF-8 NFC
        rawResponse = _local.getResponse("""
            GET /context/swedish-%C3%A5.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        if (filesystemSupportsNFCAccess)
        {
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), is("hi a-with-circle"));
        }
        else
        {
            assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        }
    }

    @Test
    public void testGzip() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path file0 = docRoot.resolve("data0.txt");
        Files.writeString(file0, "Hello Text 0", UTF_8);
        Path file0gz = docRoot.resolve("data0.txt.gz");
        Files.writeString(file0gz, "fake gzip", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);
        _rootResourceHandler.setEtags(true);

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "12"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeader(HttpHeader.ETAG));
        assertThat(response, not(containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip")));
        body = response.getContent();
        assertThat(body, containsString("Hello Text 0"));
        String etag = response.get(HttpHeader.ETAG);
        String etagGzip = EtagUtils.rewriteWithSuffix(etag, "--gzip");

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connect: close\r
            Accept-Encoding: gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt.gz HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/gzip"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain gzip variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagGzip)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt.gz HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: W/"wobble"\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "application/gzip"));
        assertThat(response, not(containsHeader(HttpHeader.CONTENT_ENCODING)));
        assertThat("Should not contain gzip variant", response, not(containsHeaderValue(HttpHeader.ETAG, etagGzip)));
        assertThat("Should have a different ETag", response, containsHeader(HttpHeader.ETAG));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));

        String badEtagGzip = EtagUtils.rewriteWithSuffix(etag, "-gzip");
        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", badEtagGzip));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(not(HttpStatus.NOT_MODIFIED_304)));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etagGzip));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: gzip\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etagGzip));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etagGzip));

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Accept-Encoding: gzip\r
            If-None-Match: W/"foobar",@ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response, containsHeaderValue(HttpHeader.ETAG, etag));
    }

    @Test
    public void testGzipEquivalentFileExtensions() throws Exception
    {
        setupBigFiles(docRoot);
        _rootResourceHandler.setGzipEquivalentFileExtensions(List.of(CompressedContentFormat.GZIP.getExtension()));

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt.gz HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("gzip"));

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is(nullValue()));
    }

    @Test
    public void testHeaders() throws Exception
    {
        setupSimpleText(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get(CONTENT_TYPE), equalTo("text/plain"));
        assertThat(response.get(LAST_MODIFIED), notNullValue());
        assertThat(response.get(CONTENT_LENGTH), equalTo("11"));
        assertThat(response.getContent(), containsString("simple text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Hello World",
        "Now is the time for all good men to come to the aid of the party"
    })
    public void testIfETag(String content) throws Exception
    {
        Files.writeString(docRoot.resolve("file.txt"), content, UTF_8);
        _rootResourceHandler.setEtags(true);

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeader(HttpHeader.ETAG));

        String etag = response.get(HttpHeader.ETAG);

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            If-None-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-None-Match: wibble,@ETAG@,wobble\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-None-Match: wibble\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-None-Match: wibble, wobble\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-Match: @ETAG@\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-Match: wibble,@ETAG@,wobble\r
            \r
            """.replace("@ETAG@", etag));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-Match: wibble\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: test\r
            Connection: close\r
            If-Match: wibble, wobble\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Hello World",
        "Now is the time for all good men to come to the aid of the party"
    })
    public void testIfModified(String content) throws Exception
    {
        Path file = docRoot.resolve("file.txt");

        /* TODO: need way to configure resource cache?
        resourceHandler.setInitParameter("maxCacheSize", "4096");
        resourceHandler.setInitParameter("maxCachedFileSize", "25");
        resourceHandler.setInitParameter("maxCachedFiles", "100");
         */

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        Files.writeString(file, content, UTF_8);

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeader(HttpHeader.LAST_MODIFIED));

        String lastModified = response.get(HttpHeader.LAST_MODIFIED);

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            If-Modified-Since: @LASTMODIFIED@\r
            \r
            """.replace("@LASTMODIFIED@", lastModified));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            If-Modified-Since: @DATE@\r
            \r
            """.replace("@DATE@", DateGenerator.formatDate(System.currentTimeMillis() - 10000)));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            If-Modified-Since: @DATE@\r
            \r
            """.replace("@DATE@", DateGenerator.formatDate(System.currentTimeMillis() + 10000)));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            If-Unmodified-Since: @DATE@\r
            \r
            """.replace("@DATE@", DateGenerator.formatDate(System.currentTimeMillis() + 10000)));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/file.txt HTTP/1.1\r
            Host:test\r
            Connection: close\r
            If-Unmodified-Since: @DATE@\r
            \r
            """.replace("@DATE@", DateGenerator.formatDate(System.currentTimeMillis() - 10000)));
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testIfModifiedSince() throws Exception
    {
        setupSimpleText(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get(LAST_MODIFIED), notNullValue());
        assertThat(response.getContent(), containsString("simple text"));
        String lastModified = response.get(LAST_MODIFIED);

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Modified-Since: %s\r
                \r
                """.formatted(lastModified)));

        assertThat(response.getStatus(), equalTo(304));
        assertThat(response.getContent(), is(""));
    }

    @Test
    public void testIfUnmodifiedSinceWithModifiedFile() throws Exception
    {
        Path testFile = docRoot.resolve("test-unmodified-since-file.txt");
        Files.writeString(testFile, "some content\n");

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/test-unmodified-since-file.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), equalTo("some content\n"));
        assertThat(response.get(LAST_MODIFIED), notNullValue());
        String lastModified = response.get(LAST_MODIFIED);

        Thread.sleep(1000);

        Files.writeString(testFile, "some more content\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        response = HttpTester.parseResponse(_local.getResponse("""
            GET /context/test-unmodified-since-file.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            If-Unmodified-Since: %s \r
            \r
            """.formatted(lastModified)));
        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testIfUnmodifiedSinceWithUnmodifiedFile() throws Exception
    {
        setupSimpleText(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(LAST_MODIFIED), notNullValue());
        assertThat(response.getContent(), containsString("simple text"));
        String lastModified = response.get(LAST_MODIFIED);

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Unmodified-Since: %s\r
                \r
                """.formatted(lastModified)));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("simple text"));
    }

    @Test
    public void testJettyDirListing() throws Exception
    {
        copySimpleTestResource(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("jetty-dir.css"));
        assertThat(response.getContent(), containsString("Directory: /context/"));
        assertThat(response.getContent(), containsString("big.txt"));
        assertThat(response.getContent(), containsString("directory"));
        assertThat(response.getContent(), containsString("simple.txt"));
    }

    @Test
    public void testJettyDirRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(301));
        assertThat(response.get(LOCATION), endsWith("/context/"));
    }

    /**
     * Tests to attempt to break out of the Context restrictions by
     * abusing encoding (or lack thereof), listing output,
     * welcome file behaviors, and more.
     */
    @ParameterizedTest
    @MethodSource("contextBreakoutScenarios")
    public void testListingContextBreakout(ResourceHandlerTest.Scenario scenario) throws Exception
    {
        _rootResourceHandler.setDirAllowed(true);
        _rootResourceHandler.setWelcomeFiles("index.html");

        /* create some content in the docroot */
        Path index = docRoot.resolve("index.html");
        Files.writeString(index, "<h1>Hello Index</h1>", UTF_8);

        if (!OS.WINDOWS.isCurrentOs())
        {
            // FileSystem should support question dirs
            assumeMkDirSupported(docRoot, "dir?");
        }

        // FileSystem should support semicolon dirs
        assumeMkDirSupported(docRoot, "dir;");

        /* create some content outside of the docroot */
        Path sekret = workDir.getPath().resolve("sekret");
        FS.ensureDirExists(sekret);
        Path pass = sekret.resolve("pass");
        Files.writeString(pass, "Sssh, you shouldn't be seeing this", UTF_8);

        /* At this point we have the following
         * testListingContextBreakout/
         * |-- docroot
         * |   |-- index.html
         * |   |-- dir?   (Might be missing on Windows)
         * |   |-- dir;
         * `-- sekret
         *     `-- pass
         */

        String rawResponse = _local.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    /**
     * A regression on windows allowed the directory listing show
     * the fully qualified paths within the directory listing.
     * This test ensures that this behavior will not arise again.
     */
    @Test
    public void testListingFilenamesOnly() throws Exception
    {
        /* create some content in the docroot */
        FS.ensureDirExists(docRoot);
        Path one = docRoot.resolve("one");
        FS.ensureDirExists(one);
        Path deep = one.resolve("deep");
        FS.ensureDirExists(deep);
        FS.touch(deep.resolve("foo"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        String resBasePath = docRoot.toAbsolutePath().toString();

        String req1 = """
            GET /context/one/deep/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;
        String rawResponse = _local.getResponse(req1);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        String body = response.getContent();
        assertThat(body, containsString("/foo"));
        assertThat(body, not(containsString(resBasePath)));
    }

    @Test
    public void testListingProperUrlEncoding() throws Exception
    {
        /* create some content in the docroot */

        Path wackyDir = docRoot.resolve("dir;"); // this should not be double-encoded.
        FS.ensureDirExists(wackyDir);

        FS.ensureDirExists(wackyDir.resolve("four"));
        FS.ensureDirExists(wackyDir.resolve("five"));
        FS.ensureDirExists(wackyDir.resolve("six"));

        /* At this point we have the following
         * testListingProperUrlEncoding/
         * `-- docroot
         *     `-- dir;
         *         |-- five
         *         |-- four
         *         `-- six
         */

        // First send request in improper, unencoded way.
        // Since this is interpreted as a path parameter, this raw ';' should not
        // make its way down to the ResourceService
        String rawResponse = _local.getResponse("""
            GET /context/dir;/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        // Now send request in proper, encoded format.
        rawResponse = _local.getResponse("""
            GET /context/dir%3B/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        String body = response.getContent();
        // Should not see double-encoded ";"
        // First encoding: ";" -> "%3b"
        // Second encoding: "%3B" -> "%253B" (BAD!)
        assertThat(body, not(containsString("%253B")));

        assertThat(body, containsString("/dir%3B/"));
        assertThat(body, containsString("/dir%3B/four/"));
        assertThat(body, containsString("/dir%3B/five/"));
        assertThat(body, containsString("/dir%3B/six/"));
    }

    @Test
    public void testListingWithQuestionMarks() throws Exception
    {
        /* create some content in the docroot */
        FS.ensureDirExists(docRoot.resolve("one"));
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        // Creating dir 'f??r' (Might not work in Windows)
        assumeMkDirSupported(docRoot, "f??r");

        String rawResponse = _local.getResponse("""
            GET /context/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String body = response.getContent();
        assertThat(body, containsString("f??r"));
    }

    /**
     * <p>
     * Tests to ensure that when requesting a legit directory listing, you
     * cannot arbitrarily include XSS in the output via careful manipulation
     * of the request path.
     * </p>
     * <p>
     * This is mainly a test of how the raw request details evolve over time, and
     * migrate through the ResourceHandler before it hits the
     * ResourceListing.getAsXHTML for output production
     * </p>
     */
    @Test
    public void testListingXSS() throws Exception
    {
        // Allow unsafe URI requests for this test case specifically
        // The requests below abuse the path-param features of URI, and the default UriCompliance mode
        // will prevent the use those requests as a 400 Bad Request: Ambiguous URI empty segment
        HttpConfiguration httpConfiguration = _local.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration();
        httpConfiguration.setUriCompliance(UriCompliance.UNSAFE);

        /* create some content in the docroot */
        Path one = docRoot.resolve("one");
        FS.ensureDirExists(one);
        FS.ensureDirExists(docRoot.resolve("two"));
        FS.ensureDirExists(docRoot.resolve("three"));

        Path alert = one.resolve("onmouseclick='alert(oops)'");
        FS.touch(alert);

        /*
         * Intentionally bad request URI. Sending a non-encoded URI with typically
         * encoded characters '<', '>', and '"', using the path-param feature of the
         * URI spec to still produce a listing.  This path-param value should not make it
         * down to the ResourceListing.getAsXHTML() method.
         */
        String req1 = """
            GET /context/;<script>window.alert("hi");</script> HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;
        String rawResponse = _local.getResponse(req1);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String body = response.getContent();
        assertThat(body, not(containsString("<script>")));

        req1 = """
            GET /context/one/;"onmouseover='alert(document.location)' HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;

        rawResponse = _local.getResponse(req1);
        response = HttpTester.parseResponse(rawResponse);

        body = response.getContent();

        assertThat(body, not(containsString(";\"onmouseover")));
    }

    @Test
    public void testNonExistentFile() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/no-such-file.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(response.getContent(), Matchers.containsString("Error 404 Not Found"));
    }

    @Test
    public void testPrecompressedGzipNoopWhenNoAcceptEncoding() throws Exception
    {
        setupBigFiles(docRoot);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(CONTENT_ENCODING), is(nullValue()));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
    }

    @Test
    public void testPrecompressedGzipNoopWhenNoMatchingAcceptEncoding() throws Exception
    {
        setupBigFiles(docRoot);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: deflate\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(CONTENT_ENCODING), is(nullValue()));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file\n"));
    }

    @Test
    public void testPrecompressedGzipNoopsWhenCompressedFileDoesNotExist() throws Exception
    {
        setupSimpleText(docRoot);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: gzip\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(CONTENT_ENCODING), is(nullValue()));
        assertThat(response.getContent(), is("simple text"));
    }

    @Test
    public void testPrecompressedGzipWorks() throws Exception
    {
        setupBigFiles(docRoot);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: gzip\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("gzip"));

        // Load big.txt.gz into a byte array and assert its contents byte per byte.
        byte[] bigGzBytes = Files.readAllBytes(docRoot.resolve("big.txt.gz"));
        assertThat(response1.getContentBytes(), is(bigGzBytes));

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is(nullValue()));
        assertThat(response2.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response2.getContent(), endsWith("   400\tThis is a big file\n"));
    }

    @Test
    public void testPrecompressedPreferredEncodingOrder() throws Exception
    {
        setupBigFiles(docRoot);
        _rootResourceHandler.setEncodingCacheSize(0);
        _rootResourceHandler.setPrecompressedFormats(new CompressedContentFormat("zip", ".zip"), CompressedContentFormat.GZIP);

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: zip,gzip\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("zip"));

        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP, new CompressedContentFormat("zip", ".zip"));

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: gzip,zip\r
                \r
                """));

        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is("gzip"));
    }

    @Test
    public void testPrecompressedPreferredEncodingOrderWithQuality() throws Exception
    {
        setupBigFiles(docRoot);
        _rootResourceHandler.setEncodingCacheSize(0);
        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP, new CompressedContentFormat("zip", ".zip"));

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: zip;q=0.5,gzip;q=1.0\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("gzip"));

        _rootResourceHandler.setPrecompressedFormats(new CompressedContentFormat("zip", ".zip"), CompressedContentFormat.GZIP);

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: zip;q=1.0,gzip;q=0.5\r
                \r
                """));
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is("zip"));
    }

    @Test
    public void testProgrammaticCustomCompressionFormats() throws Exception
    {
        Files.writeString(docRoot.resolve("data0.txt"), "Hello Text 0", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.br"), "fake brotli", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.gz"), "fake gzip", UTF_8);
        Files.writeString(docRoot.resolve("data0.txt.bz2"), "fake bzip2", UTF_8);

        _rootResourceHandler.setPrecompressedFormats(
            new CompressedContentFormat("bzip2", ".bz2"),
            new CompressedContentFormat("gzip", ".gz"),
            new CompressedContentFormat("br", ".br")
        );

        String rawResponse;
        HttpTester.Response response;
        String body;

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Connection: close\r
            Accept-Encoding: bzip2, br, gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "10"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "bzip2"));
        body = response.getContent();
        assertThat(body, containsString("fake bzip2"));

        // TODO: show accept-encoding search order issue (shouldn't this request return data0.txt.br?)

        rawResponse = _local.getResponse("""
            GET /context/data0.txt HTTP/1.1\r
            Host: localhost:8080\r
            Accept-Encoding: br, gzip\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_LENGTH, "9"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_TYPE, "text/plain"));
        assertThat(response, containsHeaderValue(HttpHeader.VARY, "Accept-Encoding"));
        assertThat(response, containsHeaderValue(HttpHeader.CONTENT_ENCODING, "gzip"));
        body = response.getContent();
        assertThat(body, containsString("fake gzip"));
    }

    @ParameterizedTest
    @MethodSource("rangeScenarios")
    @Disabled
    public void testRangeRequests(ResourceHandlerTest.Scenario scenario) throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path data = docRoot.resolve("data.txt");
        Files.writeString(data, "01234567890123456789012345678901234567890123456789012345678901234567890123456789", UTF_8);

        // test a range request with a file with no suffix, therefore no mimetype
        Path nofilesuffix = docRoot.resolve("nofilesuffix");
        Files.writeString(nofilesuffix, "01234567890123456789012345678901234567890123456789012345678901234567890123456789", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setAcceptRanges(true);

        String rawResponse = _local.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    @Test
    public void testRelativeRedirect() throws Exception
    {
        Path dir = docRoot.resolve("dir");
        FS.ensureDirExists(dir);
        Path index = dir.resolve("index.html");
        Files.writeString(index, "<h1>Hello Index</h1>", UTF_8);

        getLocalConnectorConfig().setRelativeRedirectAllowed(true);

        _rootResourceHandler.setRedirectWelcome(true);
        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setWelcomeFiles("index.html");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/dir HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "/context/dir/"));

        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "/context/dir/index.html"));

        rawResponse = _local.getResponse("""
            GET /context/dir/index.html/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "/context/dir/index.html"));
    }

    @Test
    public void testResourceRedirect() throws Exception
    {
        setupSimpleText(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/simple.txt/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/context/simple.txt"));
    }

    @Test
    public void testSlowBiggest() throws Exception
    {
        _local.setIdleTimeout(9000);
        setupBigFiles(docRoot);

        Path biggest = docRoot.resolve("biggest.txt");
        try (OutputStream out = Files.newOutputStream(biggest))
        {
            for (int i = 0; i < 10; i++)
            {
                try (InputStream in = Files.newInputStream(docRoot.resolve("bigger.txt")))
                {
                    IO.copy(in, out);
                }
            }
            out.write("\nTHE END\n".getBytes(StandardCharsets.UTF_8));
        }

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/biggest.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), endsWith("\nTHE END\n"));
    }

    @Test
    public void testSymLinks() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path dir = docRoot.resolve("dir");
        Path dirLink = docRoot.resolve("dirlink");
        Path dirRLink = docRoot.resolve("dirrlink");
        FS.ensureDirExists(dir);
        Path foobar = dir.resolve("foobar.txt");
        Path link = dir.resolve("link.txt");
        Path rLink = dir.resolve("rlink.txt");
        Files.writeString(foobar, "Foo Bar", UTF_8);

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/dir/foobar.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Foo Bar"));

        if (!OS.WINDOWS.isCurrentOs())
        {
            Files.createSymbolicLink(dirLink, dir);
            Files.createSymbolicLink(dirRLink, new File("dir").toPath());
            Files.createSymbolicLink(link, foobar);
            Files.createSymbolicLink(rLink, new File("foobar.txt").toPath());
            rawResponse = _local.getResponse("""
                GET /context/dir/link.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

            rawResponse = _local.getResponse("""
                GET /context/dir/rlink.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

            rawResponse = _local.getResponse("""
                GET /context/dirlink/foobar.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

            rawResponse = _local.getResponse("""
                GET /context/dirrlink/foobar.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

            rawResponse = _local.getResponse("""
                GET /context/dirlink/link.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

            rawResponse = _local.getResponse("""
                GET /context/dirrlink/rlink.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    @Test
    public void testWelcome() throws Exception
    {
        copySimpleTestResource(docRoot);
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/directory/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @ParameterizedTest
    @MethodSource("welcomeScenarios")
    public void testWelcome(ResourceHandlerTest.Scenario scenario) throws Exception
    {
        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);
        _rootResourceHandler.setWelcomeFiles("index.html", "index.htm");

        Path one = docRoot.resolve("one");
        Path two = docRoot.resolve("two");
        Path three = docRoot.resolve("three");
        FS.ensureDirExists(one);
        FS.ensureDirExists(two);
        FS.ensureDirExists(three);

        Files.writeString(one.resolve("index.htm"), "<h1>Hello Inde</h1>", UTF_8);
        Files.writeString(two.resolve("index.html"), "<h1>Hello Index</h1>", UTF_8);

        Files.writeString(three.resolve("index.html"), "<h1>Three Index</h1>", UTF_8);
        Files.writeString(three.resolve("index.htm"), "<h1>Three Inde</h1>", UTF_8);

        String rawResponse = _local.getResponse(scenario.rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(scenario.expectedStatus));
        if (scenario.extraAsserts != null)
            scenario.extraAsserts.accept(response);
    }

    @Test
    public void testWelcomeDirWithQuestion() throws Exception
    {
        setupQuestionMarkDir(docRoot);
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/dir? HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/dir%3F HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.getField(LOCATION).getValue(), endsWith("/context/dir%3F/"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/dir%3F/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Hello"));

        _rootResourceHandler.setRedirectWelcome(true);
        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/dir%3F/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.getField(LOCATION).getValue(), endsWith("/context/dir%3F/welcome.txt"));
    }

    @Test
    public void testWelcomeMultipleDefaultServletsDifferentBases() throws Exception
    {
        _rootResourceHandler.setWelcomeFiles("index.html", "index.htm");

        Path dir = docRoot.resolve("dir");
        FS.ensureDirExists(dir);
        Path inde = dir.resolve("index.htm");
        Path index = dir.resolve("index.html");

        Path altRoot = workDir.getPath().resolve("altroot");
        Path altDir = altRoot.resolve("dir");
        FS.ensureDirExists(altDir);
        Path altInde = altDir.resolve("index.htm");
        Path altIndex = altDir.resolve("index.html");

        ResourceHandler altResourceHandler = new ResourceHandler();
        altResourceHandler.setBaseResource(ResourceFactory.root().newResource(altRoot));
        altResourceHandler.setDirAllowed(false); // Cannot see listings
        altResourceHandler.setRedirectWelcome(false);
        altResourceHandler.setWelcomeFiles("index.html", "index.htm");
        ContextHandler altContext = new ContextHandler("/context/alt");
        altContext.setHandler(altResourceHandler);
        _contextHandlerCollection.addHandler(altContext);
        altContext.start(); // Correct behavior, after ContextHandlerCollection is started, it's on us to start the handler.

        ResourceHandler otherResourceHandler = new ResourceHandler();
        otherResourceHandler.setBaseResource(ResourceFactory.root().newResource(altRoot));
        otherResourceHandler.setDirAllowed(true); // Can see listings
        otherResourceHandler.setRedirectWelcome(false);
        otherResourceHandler.setWelcomeFiles("index.html", "index.htm");
        ContextHandler otherContext = new ContextHandler("/context/other");
        otherContext.setHandler(otherResourceHandler);
        _contextHandlerCollection.addHandler(otherContext);
        otherContext.start(); // Correct behavior, after ContextHandlerCollection is started, it's on us to start the handler.

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(false);

        String rawResponse;
        HttpTester.Response response;

        // Test other default, should see directory
        rawResponse = _local.getResponse("""
            GET /context/other/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<title>Directory: /context/other/</title>"));

        // Test alt default, should see no directory listing output (dirAllowed == false per config)
        rawResponse = _local.getResponse("""
            GET /context/alt/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        // Test alt welcome file, there's no index.html here yet, so let's create it and try
        // accessing it directly
        Files.writeString(altIndex, "<h1>Alt Index</h1>", UTF_8);
        rawResponse = _local.getResponse("""
            GET /context/alt/dir/index.html HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));

        // Test alt welcome file, there now exists an index.html, lets try accessing
        // it via the welcome file behaviors
        rawResponse = _local.getResponse("""
            GET /context/alt/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));

        // Let's create an index.htm (no 'l') and see if the welcome file logic holds,
        // we should still see the original `index.html` as that's the first welcome
        // file listed
        Files.writeString(altInde, "<h1>Alt Inde</h1>", UTF_8);
        rawResponse = _local.getResponse("""
            GET /context/alt/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Alt Index</h1>"));

        // Let's try deleting the `index.html` and accessing the welcome file at `index.htm`
        // We skip this section of the test if the OS or filesystem doesn't support instantaneous delete
        // such as what happens on Microsoft Windows.
        if (deleteFile(altIndex))
        {
            // Access welcome file `index.htm` via the directory request.
            rawResponse = _local.getResponse("""
                GET /context/alt/dir/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("<h1>Alt Inde</h1>"));

            // Delete the welcome file `index.htm`, and access the directory.
            // We should see no directory listing output (dirAllowed == false per config)
            if (deleteFile(altInde))
            {
                rawResponse = _local.getResponse("""
                    GET /context/alt/dir/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """);
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            }
        }

        // Test normal default
        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        Files.writeString(index, "<h1>Hello Index</h1>", UTF_8);
        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"));

        Files.writeString(inde, "<h1>Hello Inde</h1>", UTF_8);
        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("<h1>Hello Index</h1>"));

        if (deleteFile(index))
        {
            rawResponse = _local.getResponse("""
                GET /context/dir/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("<h1>Hello Inde</h1>"));

            if (deleteFile(inde))
            {
                rawResponse = _local.getResponse("""
                    GET /context/dir/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """);
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            }
        }
    }

    @Test
    public void testWelcomeRedirect() throws Exception
    {
        copySimpleTestResource(docRoot);
        _rootResourceHandler.setRedirectWelcome(true);
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/directory/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.get(LOCATION), containsString("/context/directory/welcome.txt"));
    }

    @Test
    public void testWelcomeRedirectAlt() throws Exception
    {
        Path dir = docRoot.resolve("dir");
        FS.ensureDirExists(dir);
        Path inde = dir.resolve("index.htm");
        Path index = dir.resolve("index.html");

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(true);
        _rootResourceHandler.setWelcomeFiles("index.html", "index.htm");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));

        Files.writeString(index, "<h1>Hello Index</h1>", UTF_8);
        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "http://local/context/dir/index.html"));

        Files.writeString(inde, "<h1>Hello Inde</h1>", UTF_8);
        rawResponse = _local.getResponse("""
            GET /context/dir HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "http://local/context/dir/"));

        rawResponse = _local.getResponse("""
            GET /context/dir/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, headerValue("Location", "http://local/context/dir/index.html"));

        if (deleteFile(index))
        {
            rawResponse = _local.getResponse("""
                GET /context/dir/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """);
            response = HttpTester.parseResponse(rawResponse);
            assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
            assertThat(response, headerValue("Location", "http://local/context/dir/index.htm"));

            if (deleteFile(inde))
            {
                rawResponse = _local.getResponse("""
                    GET /context/dir/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """);
                response = HttpTester.parseResponse(rawResponse);
                assertThat(response.toString(), response.getStatus(), is(HttpStatus.FORBIDDEN_403));
            }
        }
    }

    /**
     * Ensure that oddball directory names are served with proper escaping
     */
    @Test
    public void testWelcomeRedirectDirWithQuestion() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path dir = assumeMkDirSupported(docRoot, "dir?");

        Path index = dir.resolve("index.html");
        Files.writeString(index, "<h1>Hello Index</h1>", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(true);
        _rootResourceHandler.setWelcomeFiles("index.html");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/dir%3F/index.html HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));

        rawResponse = _local.getResponse("""
            GET /context/dir%3F HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://local/context/dir%3F/"));

        rawResponse = _local.getResponse("""
            GET /context/dir%3F/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://local/context/dir%3F/index.html"));
    }

    /**
     * Ensure that oddball directory names are served with proper escaping
     */
    @Test
    public void testWelcomeRedirectDirWithSemicolon() throws Exception
    {
        FS.ensureDirExists(docRoot);
        Path dir = assumeMkDirSupported(docRoot, "dir;");

        Path index = dir.resolve("index.html");
        Files.writeString(index, "<h1>Hello Index</h1>", UTF_8);

        _rootResourceHandler.setDirAllowed(false);
        _rootResourceHandler.setRedirectWelcome(true);
        _rootResourceHandler.setWelcomeFiles("index.html");

        String rawResponse;
        HttpTester.Response response;

        rawResponse = _local.getResponse("""
            GET /context/dir%3B HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://local/context/dir%3B/"));

        rawResponse = _local.getResponse("""
            GET /context/dir%3B/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """);
        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.toString(), response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response, containsHeaderValue("Location", "http://local/context/dir%3B/index.html"));
    }

    private void copyBigText(Path base) throws IOException
    {
        Path bigSrc = MavenTestingUtils.getTestResourcePathFile("simple/big.txt");
        Path big = base.resolve("big.txt");
        Files.copy(bigSrc, big);
    }

    private void copySimpleTestResource(Path base) throws IOException
    {
        Path simpleSrc = MavenTestingUtils.getTestResourcePathDir("simple");
        try (Stream<Path> walk = Files.walk(simpleSrc, 4))
        {
            List<Path> testSources = walk
                .filter(Files::isRegularFile)
                .map(simpleSrc::relativize).toList();
            for (Path testSourceRelative : testSources)
            {
                Path src = simpleSrc.resolve(testSourceRelative);
                Path dest = base.resolve(testSourceRelative);
                FS.ensureDirExists(dest.getParent());
                Files.copy(src, dest);
            }
        }
    }

    private boolean deleteFile(Path file) throws IOException
    {
        if (!Files.exists(file))
            return true;

        // Some OS's (Windows) do not seem to like to delete content that was recently created.
        // Attempt a delete and if it fails, attempt a rename/move.
        try
        {
            Files.delete(file);
        }
        catch (IOException ignore)
        {
            Path deletedDir = MavenTestingUtils.getTargetTestingPath(".deleted");
            FS.ensureDirExists(deletedDir);
            Path dest = Files.createTempFile(deletedDir, file.getFileName().toString(), "deleted");
            try
            {
                Files.move(file, dest);
            }
            catch (UnsupportedOperationException | IOException e)
            {
                System.err.println("WARNING: unable to move file out of the way: " + file);
            }
        }

        return !Files.exists(file);
    }

    private HttpConfiguration getLocalConnectorConfig()
    {
        return _local.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration();
    }

    private void setupBigFiles(Path base) throws Exception
    {
        Path bigger = base.resolve("bigger.txt");
        Path bigGz = base.resolve("big.txt.gz");
        Path bigZip = base.resolve("big.txt.zip");

        copyBigText(base);
        Path big = base.resolve("big.txt");

        try (OutputStream out = Files.newOutputStream(bigger))
        {
            for (int i = 0; i < 100; i++)
            {
                try (InputStream in = Files.newInputStream(big))
                {
                    IO.copy(in, out);
                }
            }
        }

        try (OutputStream out = Files.newOutputStream(bigGz);
             OutputStream gzOut = new GZIPOutputStream(out))
        {
            try (InputStream in = Files.newInputStream(big))
            {
                IO.copy(in, gzOut);
            }
        }

        try (OutputStream out = Files.newOutputStream(bigZip);
             ZipOutputStream zipOut = new ZipOutputStream(out))
        {
            zipOut.putNextEntry(new ZipEntry(big.getFileName().toString()));
            try (InputStream in = Files.newInputStream(big))
            {
                IO.copy(in, zipOut);
            }
        }
    }

    private void setupQuestionMarkDir(Path base) throws IOException
    {
        Path dirQ = base.resolve("dir?");
        Files.createDirectories(dirQ);
        Path welcome = dirQ.resolve("welcome.txt");
        Files.writeString(welcome, "Hello");
    }

    private void setupSimpleText(Path base) throws IOException
    {
        Path simpleSrc = MavenTestingUtils.getTestResourcePathFile("simple/simple.txt");
        Files.copy(simpleSrc, base.resolve("simple.txt"));
    }

    public static class Scenarios extends ArrayList<Arguments>
    {
        public void addScenario(String rawRequest, int expectedStatus)
        {
            add(Arguments.of(new ResourceHandlerTest.Scenario(rawRequest, expectedStatus)));
        }

        public void addScenario(String description, String rawRequest, int expectedStatus)
        {
            add(Arguments.of(new ResourceHandlerTest.Scenario(description, rawRequest, expectedStatus)));
        }

        public void addScenario(String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            add(Arguments.of(new ResourceHandlerTest.Scenario(rawRequest, expectedStatus, extraAsserts)));
        }

        public void addScenario(String description, String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            add(Arguments.of(new ResourceHandlerTest.Scenario(description, rawRequest, expectedStatus, extraAsserts)));
        }
    }

    public static class Scenario
    {
        public final String rawRequest;
        public final int expectedStatus;
        private final String description;
        public Consumer<HttpTester.Response> extraAsserts;

        public Scenario(String rawRequest, int expectedStatus)
        {
            this.description = firstLine(rawRequest);
            this.rawRequest = rawRequest;
            this.expectedStatus = expectedStatus;
        }

        public Scenario(String description, String rawRequest, int expectedStatus)
        {
            this.description = description;
            this.rawRequest = rawRequest;
            this.expectedStatus = expectedStatus;
        }

        public Scenario(String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            this(rawRequest, expectedStatus);
            this.extraAsserts = extraAsserts;
        }

        public Scenario(String description, String rawRequest, int expectedStatus, Consumer<HttpTester.Response> extraAsserts)
        {
            this(description, rawRequest, expectedStatus);
            this.extraAsserts = extraAsserts;
        }

        @Override
        public String toString()
        {
            return description;
        }

        private String firstLine(String rawRequest)
        {
            return rawRequest.split("\n")[0];
        }
    }
}
