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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.http.CachingContentFactory;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpHeader.CONTENT_LENGTH;
import static org.eclipse.jetty.http.HttpHeader.CONTENT_TYPE;
import static org.eclipse.jetty.http.HttpHeader.LAST_MODIFIED;
import static org.eclipse.jetty.http.HttpHeader.LOCATION;
import static org.eclipse.jetty.http.HttpHeader.SERVER;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resource Handler test
 *
 * TODO: increase the testing going on here
 */
public class ResourceHandlerTest
{
    private static String LN = System.getProperty("line.separator");
    private Server _server;
    private HttpConfiguration _config;
    private ServerConnector _connector;
    private LocalConnector _local;
    private ContextHandler _contextHandler;
    private ResourceHandler _resourceHandler;

    @BeforeAll
    public static void setUpResources() throws Exception
    {
        File dir = MavenTestingUtils.getTargetFile("test-classes/simple");
        File bigger = new File(dir, "bigger.txt");
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

        bigger.deleteOnExit();

        // determine how the SCM of choice checked out the big.txt EOL
        // we can't just use whatever is the OS default.
        // because, for example, a windows system using git can be configured for EOL handling using
        // local, remote, file lists, patterns, etc, rendering assumptions about the OS EOL choice
        // wrong for unit tests.
        LN = System.getProperty("line.separator");
        try (BufferedReader reader = Files.newBufferedReader(big.toPath(), StandardCharsets.UTF_8))
        {
            // a buffer large enough to capture at least 1 EOL
            char[] cbuf = new char[128];
            reader.read(cbuf);
            String sample = new String(cbuf);
            if (sample.contains("\r\n"))
            {
                LN = "\r\n";
            }
            else if (sample.contains("\n\r"))
            {
                LN = "\n\r";
            }
            else
            {
                LN = "\n";
            }
        }
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
        _resourceHandler.setBaseResource(MavenTestingUtils.getTargetFile("test-classes/simple").toPath());
        _resourceHandler.setWelcomeFiles(List.of("welcome.txt"));

        _contextHandler = new ContextHandler("/resource");
        _contextHandler.setHandler(_resourceHandler);

        _server.setHandler(_contextHandler);
        _server.start();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        _server.stop();
        _server.setHandler(null);
    }

    @Test
    public void testJettyDirRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(301));
        assertThat(response.get(LOCATION), endsWith("/resource/"));
    }

    @Test
    public void testJettyDirListing() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/ HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
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
            _local.getResponse("GET /resource/simple.txt HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.get(CONTENT_TYPE), equalTo("text/plain"));
        assertThat(response.get(LAST_MODIFIED), Matchers.notNullValue());
        assertThat(response.get(CONTENT_LENGTH), equalTo("11"));
        assertThat(response.get(SERVER), containsString("Jetty"));
        assertThat(response.getContent(), containsString("simple text"));
    }

    @Test
    public void testIfModifiedSince() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/simple.txt HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.get(LAST_MODIFIED), Matchers.notNullValue());
        assertThat(response.getContent(), containsString("simple text"));
        String lastModified = response.get(LAST_MODIFIED);

        response = HttpTester.parseResponse(_local.getResponse(
            "GET /resource/simple.txt HTTP/1.0\r\n" +
                "If-Modified-Since: " + lastModified + "\r\n" +
                "\r\n"));

        assertThat(response.getStatus(), equalTo(304));
    }

    @Test
    public void testResourceRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/simple.txt/ HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(302));
        assertThat(response.get(LOCATION), endsWith("/resource/simple.txt"));
    }

    @Test
    public void testDirectoryRedirect() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/directory HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(302));
        assertThat(response.get(LOCATION), endsWith("/resource/directory/"));
    }

    @Test
    public void testNonExistentFile() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/no-such-file.txt HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(404));
        assertThat(response.getContent(), Matchers.containsString("Error 404 Not Found"));
    }

    @Test
    public void testBigFile() throws Exception
    {
        _config.setOutputBufferSize(2048);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigFileBigBuffer() throws Exception
    {
        _config.setOutputBufferSize(16 * 1024);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigFileLittleBuffer() throws Exception
    {
        _config.setOutputBufferSize(8);

        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
        assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigger() throws Exception
    {
        try (Socket socket = new Socket("localhost", _connector.getLocalPort());)
        {
            socket.getOutputStream().write("GET /resource/bigger.txt HTTP/1.0\n\n".getBytes(StandardCharsets.ISO_8859_1));
            Thread.sleep(1000);
            String response = IO.toString(socket.getInputStream());
            assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
            assertThat(response, Matchers.containsString("   400\tThis is a big file" + LN + "     1\tThis is a big file"));
            assertThat(response, Matchers.endsWith("   400\tThis is a big file" + LN));
        }
    }

    @Test
    public void testWelcome() throws Exception
    {
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /resource/directory/ HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testWelcomeRedirect() throws Exception
    {
        try
        {
            _resourceHandler.setRedirectWelcome(true);
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/directory/ HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(302));
            assertThat(response.get(LOCATION), containsString("/resource/directory/welcome.txt"));
        }
        finally
        {
            _resourceHandler.setRedirectWelcome(false);
        }
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
            out.write("\nTHE END\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        biggest.deleteOnExit();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort());
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream())
        {
            socket.getOutputStream().write("GET /resource/biggest.txt HTTP/1.0\n\n".getBytes(StandardCharsets.ISO_8859_1));

            byte[] array = new byte[102400];
            ByteBuffer buffer = null;
            while (true)
            {
                Thread.sleep(10);
                int len = in.read(array);
                if (len < 0)
                    break;
                buffer = BufferUtil.toBuffer(array, 0, len);
                // System.err.println(++i+": "+BufferUtil.toDetailString(buffer));
            }

            assertEquals('E', buffer.get(buffer.limit() - 4));
            assertEquals('N', buffer.get(buffer.limit() - 3));
            assertEquals('D', buffer.get(buffer.limit() - 2));
        }
    }

    @Test
    public void testConditionalGetResponseCommitted() throws Exception
    {
        _config.setOutputBufferSize(8);
        _resourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(_local.getResponse("GET /resource/big.txt HTTP/1.0\r\n" +
            "If-Match: \"NO_MATCH\"\r\n" +
            "\r\n"));

        assertThat(response.getStatus(), equalTo(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testConditionalHeadResponseCommitted() throws Exception
    {
        _config.setOutputBufferSize(8);
        _resourceHandler.setEtags(true);

        HttpTester.Response response = HttpTester.parseResponse(_local.getResponse("HEAD /resource/big.txt HTTP/1.0\r\n" +
            "If-Match: \"NO_MATCH\"\r\n" +
            "\r\n"));

        assertThat(response.getStatus(), equalTo(HttpStatus.PRECONDITION_FAILED_412));
    }

    @Test
    public void testCachingFilesCached() throws Exception
    {
        // TODO explicitly turn on caching
        int expectedSize = (int)Files.size(_resourceHandler.getBaseResource().resolve("big.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        contentFactory.flushCache();
        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0));
    }

    @Test
    public void testCachingWelcomeFileCached() throws Exception
    {
        // TODO explicitly turn on caching
        int expectedSize = (int)Files.size(_resourceHandler.getBaseResource().resolve("directory/welcome.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/directory/ HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
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
                _local.getResponse("GET /resource/directory/ HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), containsString("Directory: /resource/directory/"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0));
    }

    @Test
    public void testCachingNotFoundNotCached() throws Exception
    {
        // TODO explicitly turn on caching
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/does-not-exist HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(404));
            assertThat(response.getContent(), containsString("Error 404 Not Found"));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0));
    }

    @Test
    public void testCachingMaxCachedFileSizeRespected() throws Exception
    {
        // TODO explicitly turn on caching
        int expectedSize = (int)Files.size(_resourceHandler.getBaseResource().resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        contentFactory.setMaxCachedFileSize(expectedSize);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0));

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/simple.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), equalTo("simple text"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));
    }

    @Test
    public void testCachingMaxCacheSizeRespected() throws Exception
    {
        // TODO explicitly turn on caching
        int expectedSize = (int)Files.size(_resourceHandler.getBaseResource().resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        contentFactory.setMaxCacheSize(expectedSize);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
        }

        assertThat(contentFactory.getCachedFiles(), is(0));
        assertThat(contentFactory.getCachedSize(), is(0));

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/simple.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), equalTo("simple text"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));
    }

    @Test
    public void testCachingMaxCachedFilesRespected() throws Exception
    {
        // TODO explicitly turn on caching
        int expectedSizeBig = (int)Files.size(_resourceHandler.getBaseResource().resolve("big.txt"));
        int expectedSizeSimple = (int)Files.size(_resourceHandler.getBaseResource().resolve("simple.txt"));
        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();
        contentFactory.setMaxCachedFiles(1);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/big.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), startsWith("     1\tThis is a big file"));
            assertThat(response.getContent(), endsWith("   400\tThis is a big file" + LN));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSizeBig));

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/simple.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), equalTo("simple text"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(oneOf(expectedSizeBig, expectedSizeSimple)));
    }

    @Test
    public void testCachingRefreshing() throws Exception
    {
        // TODO explicitly turn on caching
        Path tempPath = _resourceHandler.getBaseResource().resolve("temp.txt");
        try (BufferedWriter bufferedWriter = Files.newBufferedWriter(tempPath, StandardCharsets.ISO_8859_1))
        {
            bufferedWriter.write("temp file");
        }
        int expectedSize = (int)Files.size(tempPath);

        CachingContentFactory contentFactory = (CachingContentFactory)_resourceHandler.getContentFactory();

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/temp.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), equalTo("temp file"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(expectedSize));

        try (BufferedWriter bufferedWriter = Files.newBufferedWriter(tempPath, StandardCharsets.ISO_8859_1))
        {
            bufferedWriter.write("updated temp file");
        }
        int newExpectedSize = (int)Files.size(tempPath);

        for (int i = 0; i < 10; i++)
        {
            HttpTester.Response response = HttpTester.parseResponse(
                _local.getResponse("GET /resource/temp.txt HTTP/1.0\r\n\r\n"));
            assertThat(response.getStatus(), equalTo(200));
            assertThat(response.getContent(), equalTo("updated temp file"));
        }

        assertThat(contentFactory.getCachedFiles(), is(1));
        assertThat(contentFactory.getCachedSize(), is(newExpectedSize));

        Files.deleteIfExists(tempPath);
    }
}
