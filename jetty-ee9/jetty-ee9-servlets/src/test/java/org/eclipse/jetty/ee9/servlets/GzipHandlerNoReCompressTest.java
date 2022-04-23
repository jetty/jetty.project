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

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests {@link GzipHandler} in combination with {@link DefaultServlet} for ability to configure {@link GzipHandler} to
 * ignore recompress situations from upstream.
 */
public class GzipHandlerNoReCompressTest extends AbstractGzipTest
{
    public static Stream<Arguments> scenarios()
    {
        return Stream.of(
            Arguments.of("test_quotes.gz", "application/gzip"),
            Arguments.of("test_quotes.br", "application/brotli"),
            Arguments.of("test_quotes.bz2", "application/bzip2"),
            Arguments.of("test_quotes.zip", "application/zip"),
            Arguments.of("test_quotes.rar", "application/x-rar-compressed"),
            // Some images (common first)
            Arguments.of("jetty_logo.png", "image/png"),
            Arguments.of("jetty_logo.gif", "image/gif"),
            Arguments.of("jetty_logo.jpeg", "image/jpeg"),
            Arguments.of("jetty_logo.jpg", "image/jpeg"),
            // Lesser encountered images (usually found being requested from non-browser clients)
            Arguments.of("jetty_logo.bmp", "image/bmp"),
            Arguments.of("jetty_logo.tif", "image/tiff"),
            Arguments.of("jetty_logo.tiff", "image/tiff"),
            Arguments.of("jetty_logo.xcf", "image/xcf"),
            Arguments.of("jetty_logo.jp2", "image/jpeg2000")
        );
    }

    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNotGzipAlreadyCompressed(String fileName, String expectedContentType) throws Exception
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
        servletContextHandler.addServlet(TestStaticMimeTypeServlet.class, "/*");

        gzipHandler.setHandler(servletContextHandler);
        server.setHandler(gzipHandler);

        // Prepare Server File
        Path testResource = MavenTestingUtils.getTestResourcePath(fileName);
        Path file = contextDir.resolve(fileName);
        IO.copy(testResource.toFile(), file.toFile());
        String expectedSha1Sum = Sha1Sum.loadSha1(MavenTestingUtils.getTestResourceFile(fileName + ".sha1")).toUpperCase(Locale.ENGLISH);
        int fileSize = (int)Files.size(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/" + fileName);

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Headers check
        assertThat("Response[Content-Type]", response.get("Content-Type"), is(expectedContentType));
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));
        assertThat("Response[Vary]", response.get("Vary"), is(nullValue()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("Response Content Length", metadata.contentLength, is(fileSize));
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }
}
