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

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartByteRanges;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ResourceHandlerByteRangesTest
{
    private final String rangeChars = "0123456789abcdefghijklmnopqrstuvwxyz";
    private Server server;
    private ServerConnector connector;

    @BeforeEach
    public void start() throws Exception
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        Path dir = MavenTestingUtils.getTargetTestingPath(ResourceHandlerByteRangesTest.class.getSimpleName());
        FS.ensureEmpty(dir);
        Path rangeFile = dir.resolve("range.txt");
        Files.writeString(rangeFile, rangeChars);

        ResourceHandler handler = new ResourceHandler();
        handler.setBaseResource(ResourceFactory.root().newResource(dir));
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-unit=0-0", "bytes=not-integer", "bytes=0", "bytes=-", "bytes=2-1", "bytes=100-200"})
    public void testBadRange(String rangeValue) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod(HttpMethod.GET.asString());
        request.setURI("/range.txt");
        request.put(HttpHeader.HOST, "localhost");
        request.put(HttpHeader.ACCEPT_RANGES, HttpHeaderValue.BYTES);
        request.put(HttpHeader.RANGE, rangeValue);

        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            socket.write(request.generate());

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket));
            assertNotNull(response);
            assertEquals(HttpStatus.RANGE_NOT_SATISFIABLE_416, response.getStatus());
        }
    }

    @Test
    public void testNoRange() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod(HttpMethod.GET.asString());
        request.setURI("/range.txt");
        request.put(HttpHeader.HOST, "localhost");
        request.put(HttpHeader.ACCEPT_RANGES, HttpHeaderValue.BYTES);

        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            socket.write(request.generate());

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals(rangeChars, response.getContent());
        }
    }

    @Test
    public void testOneRange() throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod(HttpMethod.GET.asString());
        request.setURI("/range.txt");
        request.put(HttpHeader.HOST, "localhost");
        request.put(HttpHeader.ACCEPT_RANGES, HttpHeaderValue.BYTES);
        request.put(HttpHeader.RANGE, "bytes=2-4");

        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            socket.write(request.generate());

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket));
            assertNotNull(response);
            assertEquals(HttpStatus.PARTIAL_CONTENT_206, response.getStatus());
            assertEquals("text/plain", response.get(HttpHeader.CONTENT_TYPE));
            assertEquals("bytes 2-4/" + rangeChars.length(), response.get(HttpHeader.CONTENT_RANGE));
            assertEquals("234", response.getContent());
        }
    }

    @Test
    public void testTwoRanges() throws Exception
    {
        testTwoRanges(HttpHeader.RANGE, "multipart/byteranges");
    }

    @Test
    @Disabled
    public void testTwoRangesObsolete() throws Exception
    {
        testTwoRanges(HttpHeader.REQUEST_RANGE, "multipart/x-byteranges");
    }

    private void testTwoRanges(HttpHeader requestRangeHeader, String responseContentType) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod(HttpMethod.GET.asString());
        request.setURI("/range.txt");
        request.put(HttpHeader.HOST, "localhost");
        request.put(HttpHeader.ACCEPT_RANGES, HttpHeaderValue.BYTES);
        request.put(requestRangeHeader, "bytes=2-4,-3");

        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            socket.write(request.generate());

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket));
            assertNotNull(response);
            assertEquals(HttpStatus.PARTIAL_CONTENT_206, response.getStatus());
            String contentType = response.get(HttpHeader.CONTENT_TYPE);
            assertThat(contentType, startsWith(responseContentType));
            String boundary = MultiPart.extractBoundary(contentType);
            MultiPartByteRanges.Parts parts = new MultiPartByteRanges(boundary)
                .parse(new ByteBufferContentSource(response.getContentByteBuffer()))
                .join();
            assertEquals(2, parts.size());
            MultiPart.Part part1 = parts.get(0);
            assertEquals("text/plain", part1.getHeaders().get(HttpHeader.CONTENT_TYPE));
            assertEquals("234", Content.Source.asString(part1.getContent()));
            MultiPart.Part part2 = parts.get(1);
            assertEquals("text/plain", part2.getHeaders().get(HttpHeader.CONTENT_TYPE));
            assertEquals("xyz", Content.Source.asString(part2.getContent()));
        }
    }
}
