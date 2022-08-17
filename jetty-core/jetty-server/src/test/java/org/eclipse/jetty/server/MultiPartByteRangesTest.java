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

package org.eclipse.jetty.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.http.ByteRange;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartByteRanges;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiPartByteRangesTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testByteRanges() throws Exception
    {
        String resourceChars = "0123456789ABCDEF";
        Path resourceDir = MavenTestingUtils.getTargetTestingPath(getClass().getSimpleName());
        FS.ensureEmpty(resourceDir);
        Path resourcePath = resourceDir.resolve("range.txt");
        Files.writeString(resourcePath, resourceChars);

        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                assertTrue(request.getHeaders().contains(HttpHeader.ACCEPT_RANGES));
                assertTrue(request.getHeaders().contains(HttpHeader.RANGE));

                List<ByteRange> ranges = ByteRange.parse(request.getHeaders().getValuesList(HttpHeader.RANGE), resourceChars.length());

                String boundary = "boundary";
                try (MultiPartByteRanges.ContentSource content = new MultiPartByteRanges.ContentSource(boundary))
                {
                    ranges.forEach(range -> content.addPart(new MultiPartByteRanges.Part("text/plain", resourcePath, range)));
                    content.close();

                    response.setStatus(HttpStatus.PARTIAL_CONTENT_206);
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "multipart/byteranges; boundary=" + QuotedStringTokenizer.quote(boundary));
                    Content.copy(content, response, callback);
                }
            }
        });

        try (SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod(HttpMethod.GET.asString());
            request.put(HttpHeader.HOST, "localhost");
            request.put(HttpHeader.ACCEPT_RANGES, "bytes");
            request.put(HttpHeader.RANGE, "bytes=1-2,4-6,-4");
            socket.write(request.generate());

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket));
            assertNotNull(response);
            assertEquals(HttpStatus.PARTIAL_CONTENT_206, response.getStatus());
            String contentType = response.get(HttpHeader.CONTENT_TYPE);
            assertNotNull(contentType);

            String boundary = MultiPart.extractBoundary(contentType);
            MultiPartByteRanges byteRanges = new MultiPartByteRanges(boundary);
            byteRanges.parse(new ByteBufferContentSource(ByteBuffer.wrap(response.getContentBytes())));
            MultiPartByteRanges.Parts parts = byteRanges.join();

            assertEquals(3, parts.size());
            MultiPart.Part part1 = parts.get(0);
            assertEquals("12", Content.Source.asString(part1.getContent()));
            MultiPart.Part part2 = parts.get(1);
            assertEquals("456", Content.Source.asString(part2.getContent()));
            MultiPart.Part part3 = parts.get(2);
            assertEquals("CDEF", Content.Source.asString(part3.getContent()));
        }
    }
}
