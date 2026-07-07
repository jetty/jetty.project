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

package org.eclipse.jetty.server.handler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MinimumDataRateHandlerTest
{
    private Server server;
    private LocalConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testMinimumDataReadRate() throws Exception
    {
        long minimumReadRate = 1200;
        start(new MinimumDataRateHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> handle(request, response, callback));
                        return true;
                    }

                    if (Content.Chunk.isFailure(chunk))
                    {
                        callback.failed(chunk.getFailure());
                        return true;
                    }

                    chunk.release();
                    if (chunk.isLast())
                    {
                        response.setStatus(HttpStatus.OK_200);
                        callback.succeeded();
                        return true;
                    }
                }
            }
        }, minimumReadRate, 0));

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Content-Length: 1000\r
            \r
            """;

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            // Send 10 byte every 10 ms, should avg to ~1000
            // bytes/s, which is below the minimum read rate.
            for (int i = 0; i < 100; ++i)
            {
                Thread.sleep(10);
                endPoint.addInput(ByteBuffer.allocate(10));
            }

            ByteBuffer byteBuffer = endPoint.waitForResponse(false, 5, TimeUnit.SECONDS);
            assertNotNull(byteBuffer);
            HttpTester.Response response = HttpTester.parseResponse(ReadableBuffer.wrap(byteBuffer));
            assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
            assertThat(response.getContent(), containsString("read rate is too low"));
        }
    }

    @Test
    public void testMinimumDataReadRateFirstDemandDelayed() throws Exception
    {
        long delay = 1000;
        long minimumReadRate = 1200;
        CountDownLatch demandLatch = new CountDownLatch(1);
        start(new MinimumDataRateHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Delay the first demand, it should not trip the data rate.
                // Only enforce the data rate after the application demands for data.
                Thread.sleep(delay);
                read(request, response, callback);
                return true;
            }

            private void read(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        request.demand(() -> read(request, response, callback));
                        demandLatch.countDown();
                        return;
                    }

                    if (Content.Chunk.isFailure(chunk))
                    {
                        callback.failed(chunk.getFailure());
                        return;
                    }

                    chunk.release();
                    if (chunk.isLast())
                    {
                        response.setStatus(HttpStatus.OK_200);
                        callback.succeeded();
                        return;
                    }
                }
            }
        }, minimumReadRate, 0));

        String request = """
            POST / HTTP/1.1\r
            Host: localhost\r
            Content-Length: 1000\r
            \r
            """;

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            // Wait to send the content until the server demands.
            assertTrue(demandLatch.await(2 * delay, TimeUnit.MILLISECONDS));

            // Send the whole content.
            endPoint.addInput(ByteBuffer.allocate(1000));

            ByteBuffer byteBuffer = endPoint.waitForResponse(false, 5, TimeUnit.SECONDS);
            assertNotNull(byteBuffer);
            HttpTester.Response response = HttpTester.parseResponse(ReadableBuffer.wrap(byteBuffer));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    @Test
    public void testMinimumDataWriteRate() throws Exception
    {
        long minimumWriteRate = 1200;
        AtomicReference<Throwable> writeFailureRef = new AtomicReference<>();
        CountDownLatch writeCompleteLatch = new CountDownLatch(1);
        start(new MinimumDataRateHandler(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                write(response, 0, new Callback.Nested(callback)
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        super.failed(x);
                        writeFailureRef.set(x);
                    }

                    @Override
                    public void completed()
                    {
                        writeCompleteLatch.countDown();
                    }
                });
                return true;
            }

            private void write(Response response, int counter, Callback callback)
            {
                try
                {
                    // Write 10 bytes every 10 ms, should avg ~1000 bytes/s.
                    Thread.sleep(10);

                    if (counter < 100)
                    {
                        response.write(false, ReadableBuffer.allocate(10, false), new Callback()
                        {
                            @Override
                            public void succeeded()
                            {
                                write(response, counter + 1, callback);
                            }

                            @Override
                            public void failed(Throwable x)
                            {
                                callback.failed(x);
                            }
                        });
                    }
                    else
                    {
                        response.write(true, ReadableBuffer.allocate(0, false), callback);
                    }
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            }
        }, 0, minimumWriteRate));

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            assertTrue(writeCompleteLatch.await(5, TimeUnit.SECONDS));
            ByteBuffer byteBuffer = endPoint.waitForResponse(false, 5, TimeUnit.SECONDS);
            assertNotNull(byteBuffer);
            // The response is a 200 OK with chunked content, that has been interrupted.
            String response = StandardCharsets.UTF_8.decode(byteBuffer.slice()).toString();
            assertThat(response, containsString("HTTP/1.1 200 OK"));
            // Cannot parse a full response, since it has been interrupted.
            assertNull(HttpTester.parseResponse(ReadableBuffer.wrap(byteBuffer)));
            assertThat(writeFailureRef.get().getMessage(), containsString("write rate is too low"));
        }
    }

    @Test
    public void testMinimumDataWriteRateFirstWriteDelayed() throws Exception
    {
        long delay = 1000;
        long minimumWriteRate = 1200;
        start(new MinimumDataRateHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Delay the first write, it should not trip the data rate.
                // Only enforce the data rate after the application writes.
                Thread.sleep(delay);

                // A first small write to initialize the data rate check.
                CompletableFuture<?> future = new CompletableFuture<>();
                response.write(false, ReadableBuffer.allocate(10, false), Callback.from(future));
                future.get(5, TimeUnit.SECONDS);

                // Write the rest.
                response.write(false, ReadableBuffer.allocate(990, false), callback);
                return true;
            }
        }, 0, minimumWriteRate));

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            ByteBuffer byteBuffer = endPoint.waitForResponse(false, 5, TimeUnit.SECONDS);
            assertNotNull(byteBuffer);
            HttpTester.Response response = HttpTester.parseResponse(ReadableBuffer.wrap(byteBuffer));
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    @Test
    public void testMinimumDataWriteRateWithSingleLastWrite() throws Exception
    {
        long minimumWriteRate = 1200;
        AtomicReference<Throwable> writeFailureRef = new AtomicReference<>();
        CountDownLatch writeCompleteLatch = new CountDownLatch(1);
        start(new Handler.Wrapper(new MinimumDataRateHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Perform a single last write that takes too long to complete.
                response.write(true, ReadableBuffer.allocate(1000, false), new Callback.Nested(callback)
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        super.failed(x);
                        writeFailureRef.set(x);
                    }

                    @Override
                    public void completed()
                    {
                        writeCompleteLatch.countDown();
                    }
                });
                return true;
            }
        }, 0, minimumWriteRate))
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                return super.handle(request, new Response.Wrapper(request, response)
                {
                    @Override
                    public void write(boolean last, ReadableBuffer buffer, Callback callback)
                    {
                        // Only partially write the response to simulate TCP congestion.
                        // The data rate timeout should fire and fail the Handler callback.
                        long length = buffer.remaining() / 2;
                        ReadableBuffer partial = buffer.slice(buffer.position(), length);
                        buffer.position(buffer.position() + length);
                        super.write(false, partial, Callback.NOOP);
                        partial.release();
                    }
                }, callback);
            }
        });

        String request = """
            GET / HTTP/1.1\r
            Host: localhost\r
            \r
            """;

        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            assertTrue(writeCompleteLatch.await(5, TimeUnit.SECONDS));
            ByteBuffer byteBuffer = endPoint.waitForResponse(false, 5, TimeUnit.SECONDS);
            assertNotNull(byteBuffer);
            // The response is a 200 OK with chunked content, that has been interrupted.
            String response = StandardCharsets.UTF_8.decode(byteBuffer.slice()).toString();
            assertThat(response, containsString("HTTP/1.1 200 OK"));
            // Cannot parse a full response, since it has been interrupted.
            assertNull(HttpTester.parseResponse(ReadableBuffer.wrap(byteBuffer)));
            assertThat(writeFailureRef.get().getMessage(), containsString("write rate is too low"));
        }
    }
}
