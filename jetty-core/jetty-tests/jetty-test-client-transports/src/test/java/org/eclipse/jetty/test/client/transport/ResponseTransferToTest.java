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

package org.eclipse.jetty.test.client.transport;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResponseTransferToTest extends AbstractTest
{
    public static List<Arguments> transferToParams()
    {
        List<Arguments> arguments = new ArrayList<>();
        for (TransportType transportType : transportsNoFCGI())
        {
            for (long contentLength : List.of(4L, 1024L, 1024 * 1024L, 3 * 1024 * 1024 * 1024L))
            {
                // Only transfer 3 GiB for clear-text HTTP, the others are too slow.
                if (transportType != TransportType.HTTP && contentLength > Integer.MAX_VALUE)
                    continue;
                for (boolean chunked : List.of(false, true))
                {
                    arguments.add(Arguments.of(transportType, contentLength, chunked));
                }
            }
        }
        return arguments;
    }

    @ParameterizedTest
    @MethodSource("transferToParams")
    public void testResponseContentSource(TransportType transportType, long contentLength, boolean chunked) throws Exception
    {
        Path dir = Files.createDirectories(MavenPaths.targetTestDir(getClass().getSimpleName()));
        Path file = Files.createTempFile(dir, "file-", ".bin");
        try
        {
            try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE))
            {
                channel.position(contentLength - 1);
                channel.write(ByteBuffer.allocateDirect(1));
            }

            start(transportType, new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    if ("/transfer".equals(Request.getPathInContext(request)))
                    {
                        if (chunked)
                        {
                            try (var cb = Blocker.callback())
                            {
                                response.write(false, null, cb);
                                cb.block();
                            }
                        }

                        Content.Source source = Content.Source.from(file);
                        Content.Sink.write(response, true, source, callback);
                    }
                    else
                    {
                        callback.succeeded();
                    }
                    return true;
                }
            });
            client.setMaxConnectionsPerDestination(1);
            // Large client input buffer size to make the test faster.
            client.setResponseBufferSize(256 * 1024);

            AtomicLong length = new AtomicLong();
            CountDownLatch latch = new CountDownLatch(1);
            client.newRequest(newURI(transportType))
                .path("/transfer")
                .timeout(30, TimeUnit.SECONDS)
                .onResponseContent((r, c) -> length.addAndGet(c.remaining()))
                .send(r ->
                {
                    if (r.isSucceeded())
                        latch.countDown();
                });

            assertTrue(latch.await(20, TimeUnit.SECONDS));
            assertEquals(contentLength, length.get());

            // Make another request in the same connection to be
            // sure that there are no extra bytes sent by the server.
            List<Destination> destinations = client.getDestinations();
            assertEquals(1, destinations.size());
            AbstractConnectionPool connectionPool = (AbstractConnectionPool)destinations.get(0).getConnectionPool();
            await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(connectionPool::getIdleConnectionCount, equalTo(1));
            ContentResponse response = client.newRequest(newURI(transportType))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            Files.delete(file);
        }
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testResponseContentSourceUnknownLength(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                if ("/transfer".equals(Request.getPathInContext(request)))
                {
                    InputStream input = new ByteArrayInputStream(new byte[1024]);
                    Content.Source source = Content.Source.from(input);
                    Content.Sink.write(response, true, source, callback);
                }
                else
                {
                    callback.succeeded();
                }
                return true;
            }
        });
        client.setMaxConnectionsPerDestination(1);

        ContentResponse response = client.newRequest(newURI(transportType))
            .path("/transfer")
            .timeout(30, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
