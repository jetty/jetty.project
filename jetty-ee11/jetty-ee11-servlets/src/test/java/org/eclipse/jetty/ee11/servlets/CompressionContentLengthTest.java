//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.servlet.Servlet;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test the {@code CompressionHandler} support for the various ways that a webapp can set {@code Content-Length}.
 */
@ExtendWith(WorkDirExtension.class)
public class CompressionContentLengthTest
{
    public static Stream<Arguments> scenarios()
    {
        // The list of servlets that implement various content sending behaviors
        // some behaviors are saner than others, but they are all real world scenarios
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

        int defaultSize = 32 * 1024;
        List<Arguments> scenarios = new ArrayList<>();
        for (Compression compression : List.of(new BrotliCompression(), new GzipCompression(), new ZstandardCompression()))
        {
            for (Class<? extends Servlet> servlet : servlets)
            {
                for (CompressionWrapping compressionWrapping : CompressionWrapping.values())
                {
                    // Not compressible (not large enough)
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, 0, "empty.txt", false));
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, 16, "file-tiny.txt", false));

                    // Compressible.
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, defaultSize / 2, "file-small.txt", true));
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, defaultSize, "file-medium.txt", true));
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, defaultSize * 4, "file-large.txt", true));

                    // Not compressible (not a matching Content-Type)
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, defaultSize / 2, "file-small.mp3", false));
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, defaultSize, "file-medium.mp3", false));
                    scenarios.add(Arguments.of(compression, compressionWrapping, servlet, defaultSize * 4, "file-large.mp3", false));
                }
            }
        }

        return scenarios.stream();
    }

    public WorkDir workDir;
    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void executeScenario(Compression compression, CompressionWrapping compressionWrapping, Class<? extends Servlet> contentServlet, int fileSize, String fileName, boolean compressible) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        Path contextDir = workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResourceAsPath(contextDir);
        servletContextHandler.addServlet(contentServlet, "/*");
        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.putCompression(compression);

        switch (compressionWrapping)
        {
            case INTERNAL:
                servletContextHandler.insertHandler(compressionHandler);
                server.setHandler(servletContextHandler);
                break;
            case EXTERNAL:
                compressionHandler.setHandler(servletContextHandler);
                server.setHandler(compressionHandler);
                break;
        }

        Path file = createFile(contextDir, fileName, fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        try (HttpClient httpClient = new HttpClient())
        {
            httpClient.start();

            AtomicReference<String> contentEncoding = new AtomicReference<>();
            ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
                .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, compression.getEncodingName()))
                .path("/context/" + file.getFileName())
                .onResponseHeader((r, f) ->
                {
                    if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                        contentEncoding.set(f.getValue());
                    return true;
                })
                .timeout(15, TimeUnit.SECONDS)
                .send();

            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

            if (compressible)
                assertThat(contentEncoding.get(), is(compression.getEncodingName()));
            else
                assertNull(contentEncoding.get());

            byte[] responseContent = response.getContent();
            assertThat("(Uncompressed) Content Length", responseContent.length, is(fileSize));
            assertThat("(Uncompressed) Content Hash", Sha1Sum.calculate(responseContent), is(expectedSha1Sum));
        }
    }

    private Path createFile(Path contextDir, String fileName, int fileSize) throws IOException
    {
        Path destPath = contextDir.resolve(fileName);
        byte[] content = generateContent(fileSize);
        Files.write(destPath, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return destPath;
    }

    private byte[] generateContent(int length)
    {
        String sample = """
                Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc.
                Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque
                habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas.
                Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam
                at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate
                velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum.
                Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum
                eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa
                sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam
                consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque.
                Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse
                et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.
                """;
        String result = sample;
        while (result.length() < length)
        {
            result += sample;
        }
        // Make sure we are exactly at requested length. (truncate the extra)
        if (result.length() > length)
            result = result.substring(0, length);

        return result.getBytes(UTF_8);
    }

    public enum CompressionWrapping
    {
        INTERNAL, EXTERNAL
    }
}
