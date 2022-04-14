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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.servlet.Servlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Test the {@code GzipHandler} support for the various ways that an App can set {@code Content-Length}.
 */
public class GzipContentLengthTest extends AbstractGzipTest
{
    public static Stream<Arguments> scenarios()
    {
        // The list of servlets that implement various content sending behaviors
        // some behaviors are more sane then others, but they are all real world scenarios
        // that we have seen or had issues reported against Jetty.
        List<Class<? extends AbstractFileContentServlet>> servlets = new ArrayList<>();

        // AsyncContext create -> timeout -> onTimeout -> write-response -> complete
        servlets.add(AsyncTimeoutCompleteWrite.Default.class);
        servlets.add(AsyncTimeoutCompleteWrite.Passed.class);
        // AsyncContext create -> timeout -> onTimeout -> dispatch -> write-response
        servlets.add(AsyncTimeoutDispatchWrite.Default.class);
        servlets.add(AsyncTimeoutDispatchWrite.Passed.class);
        // AsyncContext create -> no-timeout -> scheduler.schedule -> dispatch -> write-response
        servlets.add(AsyncScheduledDispatchWrite.Default.class);
        servlets.add(AsyncScheduledDispatchWrite.Passed.class);

        // HttpOutput usage scenario from http://bugs.eclipse.org/450873
        // 1. getOutputStream()
        // 2. setHeader(content-type)
        // 3. setHeader(content-length)
        // 4. (unwrapped) HttpOutput.write(ByteBuffer)
        servlets.add(HttpOutputWriteFileContentServlet.class);

        // The following blocking scenarios are from http://bugs.eclipse.org/354014
        // Blocking
        // 1. setHeader(content-length)
        // 2. getOutputStream()
        // 3. setHeader(content-type)
        // 4. outputStream.write()
        servlets.add(BlockingServletLengthStreamTypeWrite.class);
        // Blocking
        // 1. setHeader(content-length)
        // 2. setHeader(content-type)
        // 3. getOutputStream()
        // 4. outputStream.write()
        servlets.add(BlockingServletLengthTypeStreamWrite.class);
        // Blocking
        // 1. getOutputStream()
        // 2. setHeader(content-length)
        // 3. setHeader(content-type)
        // 4. outputStream.write()
        servlets.add(BlockingServletStreamLengthTypeWrite.class);
        // Blocking
        // 1. getOutputStream()
        // 2. setHeader(content-length)
        // 3. setHeader(content-type)
        // 4. outputStream.write() (with frequent response flush)
        servlets.add(BlockingServletStreamLengthTypeWriteWithFlush.class);
        // Blocking
        // 1. getOutputStream()
        // 2. setHeader(content-type)
        // 3. setHeader(content-length)
        // 4. outputStream.write()
        servlets.add(BlockingServletStreamTypeLengthWrite.class);
        // Blocking
        // 1. setHeader(content-type)
        // 2. setHeader(content-length)
        // 3. getOutputStream()
        // 4. outputStream.write()
        servlets.add(BlockingServletTypeLengthStreamWrite.class);
        // Blocking
        // 1. setHeader(content-type)
        // 2. getOutputStream()
        // 3. setHeader(content-length)
        // 4. outputStream.write()
        servlets.add(BlockingServletTypeStreamLengthWrite.class);

        List<Arguments> scenarios = new ArrayList<>();

        for (Class<? extends Servlet> servlet : servlets)
        {
            // Not compressible (not large enough)
            scenarios.add(Arguments.of(servlet, 0, "empty.txt", false));
            scenarios.add(Arguments.of(servlet, GzipHandler.DEFAULT_MIN_GZIP_SIZE / 2, "file-tiny.txt", false));

            // Compressible.
            scenarios.add(Arguments.of(servlet, DEFAULT_OUTPUT_BUFFER_SIZE / 2, "file-small.txt", true));
            scenarios.add(Arguments.of(servlet, DEFAULT_OUTPUT_BUFFER_SIZE, "file-medium.txt", true));
            scenarios.add(Arguments.of(servlet, DEFAULT_OUTPUT_BUFFER_SIZE * 4, "file-large.txt", true));

            // Not compressible (not a matching Content-Type)
            scenarios.add(Arguments.of(servlet, DEFAULT_OUTPUT_BUFFER_SIZE / 2, "file-small.mp3", false));
            scenarios.add(Arguments.of(servlet, DEFAULT_OUTPUT_BUFFER_SIZE, "file-medium.mp3", false));
            scenarios.add(Arguments.of(servlet, DEFAULT_OUTPUT_BUFFER_SIZE * 4, "file-large.mp3", false));
        }

        return scenarios.stream();
    }

    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void executeScenario(Class<? extends Servlet> contentServlet, int fileSize, String fileName, boolean compressible) throws Exception
    {
        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(new PathResource(contextDir));
        servletContextHandler.addServlet(contentServlet, "/*");
        GzipHandler gzipHandler = new GzipHandler();

        gzipHandler.setHandler(servletContextHandler);
        server.setHandler(gzipHandler);

        Path file = createFile(contextDir, fileName, fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/" + file.getFileName().toString());

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        Matcher<String> contentEncodingMatcher = containsString(GzipHandler.GZIP);
        if (!compressible)
        {
            contentEncodingMatcher = not(contentEncodingMatcher);
        }
        assertThat("Content-Encoding", response.get("Content-Encoding"), contentEncodingMatcher);

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }
}
