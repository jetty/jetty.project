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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.eclipse.jetty.http.HttpHeader.SERVER;
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

    private static Path TEST_PATH;
    private Server _server;
    private HttpConfiguration _config;
    private ServerConnector _connector;
    private LocalConnector _local;
    private ContextHandler _contextHandler;
    private ResourceHandler _resourceHandler;

    @BeforeAll
    public static void setUpResources() throws Exception
    {
        TEST_PATH = MavenTestingUtils.getTargetFile("test-classes/simple").toPath();
        File dir = TEST_PATH.toFile();
        File bigger = new File(dir, "bigger.txt");
        File bigGz = new File(dir, "big.txt.gz");
        File bigZip = new File(dir, "big.txt.zip");
        File big = new File(dir, "big.txt");
        try (OutputStream out = new FileOutputStream(bigger))
        {
            for (int i = 0; i < 100; i++)
            {
                try (InputStream in = new FileInputStream(big))
                {
                    IO.copy(in, out);
                }
            }
        }
        try (OutputStream gzOut = new GZIPOutputStream(new FileOutputStream(bigGz)))
        {
            try (InputStream in = new FileInputStream(big))
            {
                IO.copy(in, gzOut);
            }
        }
        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(bigZip)))
        {
            zipOut.putNextEntry(new ZipEntry(big.getName()));
            try (InputStream in = new FileInputStream(big))
            {
                IO.copy(in, zipOut);
            }
        }

        bigger.deleteOnExit();
        bigGz.deleteOnExit();
        bigZip.deleteOnExit();

        Path dirQ = TEST_PATH.resolve("dir?");
        Files.createDirectories(dirQ);
        Path welcome = dirQ.resolve("welcome.txt");
        try (FileOutputStream out = new FileOutputStream(welcome.toFile()))
        {
            out.write("Hello".getBytes());
        }
        welcome.toFile().deleteOnExit();
    }

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _config = new HttpConfiguration();
        _config.setOutputBufferSize(4096);
        _connector = new ServerConnector(_server, new HttpConnectionFactory(_config));
        _local = new LocalConnector(_server);
        _server.setConnectors(new Connector[]{_connector, _local});

        _resourceHandler = new ResourceHandler();
        _resourceHandler.setWelcomeFiles(List.of("welcome.txt"));

        _contextHandler = new ContextHandler("/resource");
        _contextHandler.setHandler(_resourceHandler);
        _contextHandler.setBaseResource(Resource.newResource(TEST_PATH));

        _server.setHandler(_contextHandler);
        _server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
        _server.setHandler((Handler)null);
    }

    @Test
    @Disabled
    public void testPrecompressedGzipWorks() throws Exception
    {
        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
            Files.copy(TEST_PATH.resolve("big.txt.gz"), baos);
            assertThat(response1.getContentBytes(), is(baos.toByteArray()));
        }

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
    public void testGzipEquivalentFileExtensions() throws Exception
    {
        _resourceHandler.setGzipEquivalentFileExtensions(List.of(CompressedContentFormat.GZIP.getExtension()));

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt.gz HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("gzip"));

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is(nullValue()));
    }

    @Test
    public void testPrecompressedPreferredEncodingOrderWithQuality() throws Exception
    {
        _resourceHandler.setEncodingCacheSize(0);
        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP, new CompressedContentFormat("zip", ".zip"));

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: zip;q=0.5,gzip;q=1.0\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("gzip"));

        _resourceHandler.setPrecompressedFormats(new CompressedContentFormat("zip", ".zip"), CompressedContentFormat.GZIP);

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: zip;q=1.0,gzip;q=0.5\r
                \r
                """));
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is("zip"));
    }

    @Test
    public void testPrecompressedPreferredEncodingOrder() throws Exception
    {
        _resourceHandler.setEncodingCacheSize(0);
        _resourceHandler.setPrecompressedFormats(new CompressedContentFormat("zip", ".zip"), CompressedContentFormat.GZIP);

        HttpTester.Response response1 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: zip,gzip\r
                \r
                """));
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));
        assertThat(response1.get(CONTENT_ENCODING), is("zip"));

        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP, new CompressedContentFormat("zip", ".zip"));

        HttpTester.Response response2 = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.0\r
                Host: local\r
                Connection: close\r
                Accept-Encoding: gzip,zip\r
                \r
                """));

        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
        assertThat(response2.get(CONTENT_ENCODING), is("gzip"));
    }

    @Test
    public void testPrecompressedGzipNoopsWhenCompressedFileDoesNotExist() throws Exception
    {
        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/simple.txt HTTP/1.1\r
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
    public void testPrecompressedGzipNoopWhenNoAcceptEncoding() throws Exception
    {
        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.0\r
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
        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
    public void testJettyDirRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(301));
        assertThat(response.get(LOCATION), endsWith("/resource/"));
    }

    @Test
    public void testJettyDirListing() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("jetty-dir.css"));
        assertThat(response.getContent(), containsString("Directory: /resource/"));
        assertThat(response.getContent(), containsString("big.txt"));
        assertThat(response.getContent(), containsString("bigger.txt"));
        assertThat(response.getContent(), containsString("directory"));
        assertThat(response.getContent(), containsString("simple.txt"));
    }

    @Test
    public void testHeaders() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
        assertThat(response.get(CONTENT_TYPE), equalTo("text/plain"));
        assertThat(response.get(LAST_MODIFIED), notNullValue());
        assertThat(response.get(CONTENT_LENGTH), equalTo("11"));
        assertThat(response.get(SERVER), containsString("Jetty"));
        assertThat(response.getContent(), containsString("simple text"));
    }

    @Test
    public void testIfModifiedSince() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/simple.txt HTTP/1.1\r
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
                GET /resource/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Modified-Since: %s\r
                \r
                """.formatted(lastModified)));

        assertThat(response.getStatus(), equalTo(304));
        assertThat(response.getContent(), is(""));
    }

    @Test
    public void testIfUnmodifiedSinceWithUnmodifiedFile() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/simple.txt HTTP/1.1\r
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
                GET /resource/simple.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Unmodified-Since: %s\r
                \r
                """.formatted(lastModified)));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("simple text"));
    }

    @Test
    public void testIfUnmodifiedSinceWithModifiedFile() throws Exception
    {
        Path testFile = TEST_PATH.resolve("test-unmodified-since-file.txt");
        Files.writeString(testFile, "some content\n");

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/test-unmodified-since-file.txt HTTP/1.1\r
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
            GET /resource/test-unmodified-since-file.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            If-Unmodified-Since: %s \r
            \r
            """.formatted(lastModified)));
        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testResourceRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/simple.txt/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/resource/simple.txt"));
    }

    @Test
    public void testDirectoryRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/directory HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/resource/directory/"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/directory;JSESSIONID=12345678 HTTP/1.1\r
                Host: local\r
                Connection: close\r               
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/resource/directory/;JSESSIONID=12345678"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/directory?name=value HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/resource/directory/?name=value"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/directory;JSESSIONID=12345678?name=value HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.MOVED_TEMPORARILY_302));
        assertThat(response.get(LOCATION), endsWith("/resource/directory/;JSESSIONID=12345678?name=value"));
    }

    @Test
    public void testNonExistentFile() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/no-such-file.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));
        assertThat(response.getContent(), Matchers.containsString("Error 404 Not Found"));
    }

    @Test
    public void testBigFile() throws Exception
    {
        _config.setOutputBufferSize(2048);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
        _config.setOutputBufferSize(16 * 1024);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
        _config.setOutputBufferSize(8);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/bigger.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("   400\tThis is a big file\n     1\tThis is a big file"));
        assertThat(response.getContent(), containsString("   400\tThis is a big file\n"));
    }

    @Test
    public void testWelcome() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/directory/ HTTP/1.1\r
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
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/dir? HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.NOT_FOUND_404));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/dir%3F HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.getField(LOCATION).getValue(), endsWith("/resource/dir%3F/"));

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/dir%3F/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("Hello"));

        _resourceHandler.setRedirectWelcome(true);
        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/dir%3F/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.getField(LOCATION).getValue(), endsWith("/resource/dir%3F/welcome.txt"));
    }

    @Test
    public void testWelcomeRedirect() throws Exception
    {
        _resourceHandler.setRedirectWelcome(true);
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/directory/ HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.get(LOCATION), containsString("/resource/directory/welcome.txt"));
    }

    @Test
    public void testSlowBiggest() throws Exception
    {
        _connector.setIdleTimeout(9000);

        File dir = MavenTestingUtils.getTargetFile("test-classes/simple");
        File biggest = new File(dir, "biggest.txt");
        try (OutputStream out = new FileOutputStream(biggest))
        {
            for (int i = 0; i < 10; i++)
            {
                try (InputStream in = new FileInputStream(new File(dir, "bigger.txt")))
                {
                    IO.copy(in, out);
                }
            }
            out.write("\nTHE END\n".getBytes(StandardCharsets.UTF_8));
        }
        biggest.deleteOnExit();

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/biggest.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), endsWith("\nTHE END\n"));
    }

    @Test
    public void testConditionalGetResponseCommitted() throws Exception
    {
        _config.setOutputBufferSize(8);
        _resourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
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
        _config.setOutputBufferSize(8);
        _resourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                HEAD /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-Match: "NO_MATCH"\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testEtagIfMatchAlwaysFailsDueToWeakEtag() throws Exception
    {
        _resourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(ETAG), notNullValue());
        String etag = response.get(ETAG);

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.0\r
                Host: local\r
                Connection: close\r
                If-Match: %s\r
                \r
                """.formatted(etag)));
        assertThat(response.getStatus(), is(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testEtagIfNoneMatchNotModifiedFile() throws Exception
    {
        _resourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                \r
                """));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.get(ETAG), notNullValue());
        String etag = response.get(ETAG);

        response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/big.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-None-Match: %s\r
                \r
                """.formatted(etag)));
        assertThat(response.getStatus(), is(HttpStatus.NOT_MODIFIED_304));
        assertThat(response.getContent(), is(""));
    }

    @Test
    public void testEtagIfNoneMatchModifiedFile() throws Exception
    {
        _resourceHandler.setEtags(true);
        Path testFile = TEST_PATH.resolve("test-etag-file.txt");
        Files.writeString(testFile, "some content\n");

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("""
                GET /resource/test-etag-file.txt HTTP/1.1\r
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
                GET /resource/test-etag-file.txt HTTP/1.1\r
                Host: local\r
                Connection: close\r
                If-None-Match: %s\r
                \r
                """.formatted(etag)));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), equalTo("some different content\n"));
    }

    @Test
    public void testCachingFilesCached() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(TEST_PATH.resolve("big.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
    @Disabled
    public void testCachingPrecompressedFilesCached() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(TEST_PATH.resolve("big.txt")) +
            Files.size(TEST_PATH.resolve("big.txt.gz"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response1 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
                Files.copy(TEST_PATH.resolve("big.txt.gz"), baos);
                assertThat(response1.getContentBytes(), is(baos.toByteArray()));
            }

            HttpTester.Response response2 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
        long expectedSize = Files.size(TEST_PATH.resolve("big.txt")) +
            Files.size(TEST_PATH.resolve("big.txt.gz"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        _resourceHandler.setPrecompressedFormats(CompressedContentFormat.GZIP);
        _resourceHandler.setEtags(true);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response1 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
                Files.copy(TEST_PATH.resolve("big.txt.gz"), baos);
                assertThat(response1.getContentBytes(), is(baos.toByteArray()));
            }

            HttpTester.Response response2 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
                    GET /resource/big.txt HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    Accept-Encoding: gzip\r
                    If-None-Match: %s \r
                    \r
                    """.formatted(eTag1)));
            assertThat(response3.getStatus(), is(HttpStatus.NOT_MODIFIED_304));

            HttpTester.Response response4 = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
    public void testCachingWelcomeFileCached() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(TEST_PATH.resolve("directory/welcome.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/directory/ HTTP/1.1\r
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
    public void testCachingDirectoryNotCached() throws Exception
    {
        // TODO explicitly turn on caching
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        _resourceHandler.setWelcomeFiles(List.of()); // disable welcome files otherwise they get cached

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/directory/ HTTP/1.1\r
                    Host: local\r
                    Connection: close\r
                    \r
                    """));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(response.getContent(), containsString("Directory: /resource/directory/"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0L));
    }

    @Test
    public void testCachingNotFoundNotCached() throws Exception
    {
        // TODO explicitly turn on caching
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/does-not-exist HTTP/1.1\r
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
    public void testCachingMaxCachedFileSizeRespected() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(TEST_PATH.resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        contentFactory.setMaxCachedFileSize((int)expectedSize);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
                    GET /resource/simple.txt HTTP/1.1\r
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
    public void testCachingMaxCacheSizeRespected() throws Exception
    {
        // TODO explicitly turn on caching
        long expectedSize = Files.size(TEST_PATH.resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        contentFactory.setMaxCacheSize((int)expectedSize);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
                    GET /resource/simple.txt HTTP/1.1\r
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
        // TODO explicitly turn on caching
        long expectedSizeBig = Files.size(TEST_PATH.resolve("big.txt"));
        long expectedSizeSimple = Files.size(TEST_PATH.resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        contentFactory.setMaxCachedFiles(1);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/big.txt HTTP/1.1\r
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
                    GET /resource/simple.txt HTTP/1.1\r
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
    public void testCachingRefreshing() throws Exception
    {
        // TODO explicitly turn on caching
        Path tempPath = TEST_PATH.resolve("temp.txt");
        Files.writeString(tempPath, "temp file");
        long expectedSize = Files.size(tempPath);

        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("""
                    GET /resource/temp.txt HTTP/1.1\r
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
                    GET /resource/temp.txt HTTP/1.1\r
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
}
