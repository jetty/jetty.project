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

package org.eclipse.jetty.fcgi.server;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerFCGIConnectionTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testReadWhileHoldingPreviousChunkWithNetworkReadEndingInsideRecord(boolean splitInPadding) throws Exception
    {
        CountDownLatch firstChunkLatch = new CountDownLatch(1);
        CompletableFuture<String> contentFuture = new CompletableFuture<>();
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Hold all the chunks until the end of the content,
                // so that the network buffer they slice stays retained.
                List<Content.Chunk> chunks = new ArrayList<>();
                try
                {
                    while (true)
                    {
                        Content.Chunk chunk = request.read();
                        if (chunk == null)
                        {
                            try (Blocker.Runnable blocker = Blocker.runnable())
                            {
                                request.demand(blocker);
                                blocker.block();
                            }
                            continue;
                        }
                        chunks.add(chunk);
                        if (Content.Chunk.isFailure(chunk))
                        {
                            contentFuture.completeExceptionally(chunk.getFailure());
                            break;
                        }
                        if (chunk.hasRemaining())
                            firstChunkLatch.countDown();
                        if (chunk.isLast())
                        {
                            StringBuilder builder = new StringBuilder();
                            for (Content.Chunk c : chunks)
                            {
                                try (RetainableByteBuffer buffer = c.acquire())
                                {
                                    builder.append(buffer.getString(UTF_8));
                                }
                            }
                            contentFuture.complete(builder.toString());
                            break;
                        }
                    }
                }
                finally
                {
                    chunks.forEach(Content.Chunk::release);
                }
                callback.succeeded();
                return true;
            }
        });

        int padding = 4;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        record(bytes, FCGI.FrameType.BEGIN_REQUEST, new byte[]{0, 1, 0, 0, 0, 0, 0, 0}, 0);
        record(bytes, FCGI.FrameType.PARAMS, params(
            FCGI.Headers.REQUEST_METHOD, "POST",
            FCGI.Headers.DOCUMENT_URI, "/",
            FCGI.Headers.SERVER_PROTOCOL, "HTTP/1.1",
            "HTTP_HOST", "localhost"
        ), 0);
        record(bytes, FCGI.FrameType.PARAMS, new byte[0], 0);
        String content1 = "hello";
        record(bytes, FCGI.FrameType.STDIN, content1.getBytes(UTF_8), padding);
        int split = bytes.size();
        String content2 = "world";
        record(bytes, FCGI.FrameType.STDIN, content2.getBytes(UTF_8), 0);
        record(bytes, FCGI.FrameType.STDIN, new byte[0], 0);
        byte[] request = bytes.toByteArray();

        // The first network read ends either in the padding of the first
        // STDIN record, or in the middle of the header of the second one.
        split = splitInPadding ? split - padding / 2 : split + 3;

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();
            output.write(request, 0, split);
            output.flush();

            // Wait for the application to hold the first chunk.
            assertTrue(firstChunkLatch.await(5, TimeUnit.SECONDS));
            // Let the application try to read again while still holding the
            // first chunk, which consumes the bytes left in the network buffer.
            Thread.sleep(500);

            output.write(request, split, request.length - split);
            output.flush();

            assertEquals(content1 + content2, contentFuture.get(5, TimeUnit.SECONDS));
        }
    }

    private static void record(ByteArrayOutputStream output, FCGI.FrameType type, byte[] content, int padding)
    {
        // FastCGI version.
        output.write(1);
        output.write(type.code);
        output.write(0);
        output.write(1);
        output.write(content.length >>> 8);
        output.write(content.length & 0xFF);
        output.write(padding);
        output.write(0);
        output.writeBytes(content);
        output.writeBytes(new byte[padding]);
    }

    private static byte[] params(String... namesAndValues)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < namesAndValues.length; i += 2)
        {
            byte[] name = namesAndValues[i].getBytes(UTF_8);
            byte[] value = namesAndValues[i + 1].getBytes(UTF_8);
            output.write(name.length);
            output.write(value.length);
            output.writeBytes(name);
            output.writeBytes(value);
        }
        return output.toByteArray();
    }
}
