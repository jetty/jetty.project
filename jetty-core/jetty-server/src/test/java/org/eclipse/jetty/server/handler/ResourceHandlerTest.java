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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.eclipse.jetty.http.HttpHeader.CONTENT_ENCODING;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpHeader.ETAG;
import static org.eclipse.jetty.http.HttpHeader.LAST_MODIFIED;
import static org.eclipse.jetty.http.HttpHeader.LOCATION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

/**
 * Resource Handler test
 *
 * TODO: increase the testing going on here
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

    @BeforeEach
    public void before() throws Exception
    {
        docRoot = workDir.getEmptyPathDir().resolve("docroot");
        FS.ensureEmpty(docRoot);

        _server = new Server();
        _local = new LocalConnector(_server);
        _local.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _server.addConnector(_local);

        _rootResourceHandler = new ResourceHandler();
        _rootResourceHandler.setBaseResource(Resource.newResource(docRoot));
        _rootResourceHandler.setWelcomeFiles("welcome.txt");
        _rootResourceHandler.setRedirectWelcome(false);

        ContextHandler contextHandler = new ContextHandler("/context");
        contextHandler.setHandler(_rootResourceHandler);

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
    public void testCachingDirectoryNotCached() throws Exception
    {
        copySimpleTestResource(docRoot);
        // TODO explicitly turn on caching
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();
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
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();

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
        // TODO explicitly turn on caching
        long expectedSize = Files.size(docRoot.resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();
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
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();
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
        // TODO explicitly turn on caching
        long expectedSizeBig = Files.size(docRoot.resolve("big.txt"));
        long expectedSizeSimple = Files.size(docRoot.resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();
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
        // TODO explicitly turn on caching
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();

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
    @Disabled
    public void testCachingPrecompressedFilesCached() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(docRoot.resolve("big.txt")) +
            Files.size(docRoot.resolve("big.txt.gz"));
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();

        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

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

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        contentFactory.flushCache();
        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    @Disabled
    public void testCachingPrecompressedFilesCachedEtagged() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(docRoot.resolve("big.txt")) +
            Files.size(docRoot.resolve("big.txt.gz"));
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();

        _rootResourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);
        _rootResourceHandler.setEtags(true);

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

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        contentFactory.flushCache();
        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingRefreshing() throws Exception
    {
        // TODO explicitly turn on caching
        Path tempPath = docRoot.resolve("temp.txt");
        Files.writeString(tempPath, "temp file");
        long expectedSize = Files.size(tempPath);

        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();

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
        // TODO explicitly turn on caching
        long expectedSize = Files.size(docRoot.resolve("directory/welcome.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_rootResourceHandler.getContentFactory();

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
    public void testDirectoryRedirect() throws Exception
    {
        copySimpleTestResource(docRoot);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/directory HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/context/directory/"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/directory;JSESSIONID=12345678 HTTP/1.1\r
                Host: local\r
                Connection: close\r              
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/context/directory/;JSESSIONID=12345678"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/directory?name=value HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/context/directory/?name=value"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /context/directory;JSESSIONID=12345678?name=value HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/context/directory/;JSESSIONID=12345678?name=value"));
    }

    @Test
    public void testEtagIfMatchAlwaysFailsDueToWeakEtag() throws Exception
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
                If-Match: %s\r
                \r
                """.formatted(etag)));
        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
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
    @Disabled
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
}
