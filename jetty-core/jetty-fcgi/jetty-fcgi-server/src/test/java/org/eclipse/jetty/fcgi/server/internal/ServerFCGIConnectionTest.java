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

package org.eclipse.jetty.fcgi.server.internal;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.fcgi.generator.ClientGenerator;
import org.eclipse.jetty.fcgi.parser.ClientParser;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerFCGIConnectionTest
{
    /**
     * The application completes while its first input-buffer release is parked,
     * reproducing the input-buffer ownership handoff of issue #14403.
     */
    @Test
    public void testApplicationDispatchedAfterParsing() throws Exception
    {
        AtomicInteger handlersInvoked = new AtomicInteger();
        AtomicInteger completedTasks = new AtomicInteger();
        AtomicReference<Throwable> taskFailure = new AtomicReference<>();
        AtomicBoolean firstAcquire = new AtomicBoolean(true);
        AtomicBoolean releasedOnce = new AtomicBoolean();
        CountDownLatch inputReleaseEntered = new CountDownLatch(1);
        CountDownLatch inputReleaseProceed = new CountDownLatch(1);

        ArrayByteBufferPool.Tracking trackingPool = new ArrayByteBufferPool.Tracking();
        ByteBufferPool bufferPool = new ByteBufferPool.Wrapper(trackingPool)
        {
            @Override
            public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
            {
                RetainableByteBuffer.Mutable buffer = super.acquire(size, direct);
                if (firstAcquire.getAndSet(false))
                {
                    return new RetainableByteBuffer.Mutable.Wrapper(buffer)
                    {
                        @Override
                        public boolean release()
                        {
                            if (releasedOnce.compareAndSet(false, true))
                            {
                                inputReleaseEntered.countDown();
                                awaitLatch(inputReleaseProceed, "input buffer release was not released");
                            }
                            return super.release();
                        }
                    };
                }
                return buffer;
            }
        };

        ExecutorService handlerExecutor = Executors.newSingleThreadExecutor();
        Executor executor = task ->
        {
            handlerExecutor.execute(() ->
            {
                try
                {
                    task.run();
                }
                catch (Throwable x)
                {
                    taskFailure.set(x);
                }
                finally
                {
                    completedTasks.incrementAndGet();
                }
            });
            awaitLatch(inputReleaseEntered, "application task did not reach the input buffer release");
        };

        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 64 * 1024);
        Server server = newServer(handlersInvoked);
        ServerFCGIConnection connection = newConnection(server, executor, bufferPool, endPoint);

        endPoint.addInput(generateRequest(1));

        AtomicReference<Throwable> fillableFailure = new AtomicReference<>();
        Thread fillThread = new Thread(() -> run(connection::onFillable, fillableFailure), "fcgi-io");
        fillThread.start();
        try
        {
            fillThread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(fillThread.isAlive(), "onFillable() did not complete");

            inputReleaseProceed.countDown();
            await().atMost(10, TimeUnit.SECONDS).until(() -> completedTasks.get() == 1);
            assertEquals(1, handlersInvoked.get());
            assertThat(fillableFailure.get(), nullValue());

            Responses responses = new Responses();
            parseResponse(endPoint.takeOutput(), responses);
            responses.assertResponses(200, 1);

            endPoint.addInput(generateRequest(2));
            await().atMost(10, TimeUnit.SECONDS).until(() -> completedTasks.get() == 2);
            assertThat(taskFailure.get(), nullValue());
            parseResponse(endPoint.takeOutput(), responses);
            responses.assertResponses(200, 2);
            assertEquals(2, handlersInvoked.get());

            assertThat("Server Leaks: " + trackingPool.dumpLeaks(), trackingPool.getLeaks().size(), is(0));
        }
        finally
        {
            inputReleaseProceed.countDown();
            fillThread.join(TimeUnit.SECONDS.toMillis(10));
            handlerExecutor.shutdownNow();
            assertTrue(handlerExecutor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testApplicationDispatchedAfterParsingWithInlineExecutor() throws Exception
    {
        AtomicInteger handlersInvoked = new AtomicInteger();
        ArrayByteBufferPool.Tracking trackingPool = new ArrayByteBufferPool.Tracking();
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 64 * 1024);
        Server server = newServer(handlersInvoked);
        ServerFCGIConnection connection = newConnection(server, Runnable::run, trackingPool, endPoint);

        endPoint.addInput(generateRequest(1));

        connection.onFillable();
        assertEquals(1, handlersInvoked.get());

        Responses responses = new Responses();
        parseResponse(endPoint.takeOutput(), responses);
        responses.assertResponses(HttpStatus.OK_200, 1);
        assertThat("Server Leaks: " + trackingPool.dumpLeaks(), trackingPool.getLeaks().size(), is(0));
    }

    @Test
    public void testApplicationDispatchRejectedBeforeRequestEnd() throws Exception
    {
        AtomicInteger handlersInvoked = new AtomicInteger();
        ArrayByteBufferPool.Tracking trackingPool = new ArrayByteBufferPool.Tracking();
        Executor executor = task ->
        {
            throw new RejectedExecutionException("test");
        };
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint(new byte[0], 64 * 1024);
        Server server = newServer(handlersInvoked);
        ServerFCGIConnection connection = newConnection(server, executor, trackingPool, endPoint);

        endPoint.addInput(generateRequestHeaders(1));

        ByteBuffer output;
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            connection.onFillable();
            output = endPoint.waitForOutput(10, TimeUnit.SECONDS);
        }
        assertEquals(0, handlersInvoked.get());
        assertTrue(output != null && output.hasRemaining(), "no failure response written");
        // The failure must terminate the connection-side request even if its content has not arrived.
        Field streamField = ServerFCGIConnection.class.getDeclaredField("stream");
        streamField.setAccessible(true);
        assertThat(streamField.get(connection), nullValue());

        Responses responses = new Responses();
        parseResponse(output, responses);
        responses.assertResponses(HttpStatus.INTERNAL_SERVER_ERROR_500, 1);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat("Server Leaks: " + trackingPool.dumpLeaks(), trackingPool.getLeaks().size(), is(0)));
    }

    private static Server newServer(AtomicInteger handlersInvoked)
    {
        Server server = new Server();
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                handlersInvoked.incrementAndGet();
                // Do not read the request content, just complete the callback
                // as soon as the request arrives, like the application in
                // issue #14403.
                callback.succeeded();
                return true;
            }
        });
        return server;
    }

    private static ServerFCGIConnection newConnection(Server server, Executor executor, ByteBufferPool bufferPool, ByteArrayEndPoint endPoint)
    {
        ServerFCGIConnectionFactory connectionFactory = new ServerFCGIConnectionFactory(new HttpConfiguration());
        ServerConnector connector = new ServerConnector(server, executor, null, bufferPool, 0, 1, connectionFactory);
        return new ServerFCGIConnection(connector, endPoint, new HttpConfiguration(), false);
    }

    private static class Responses implements ClientParser.Listener
    {
        private final List<Integer> statuses = new ArrayList<>();
        private final AtomicInteger headers = new AtomicInteger();
        private final AtomicInteger ends = new AtomicInteger();

        @Override
        public void onBegin(int request, int code, String reason)
        {
            statuses.add(code);
        }

        @Override
        public boolean onHeaders(int request)
        {
            headers.incrementAndGet();
            return false;
        }

        @Override
        public boolean onEnd(int request)
        {
            ends.incrementAndGet();
            return true;
        }

        void assertResponses(int status, int count)
        {
            assertEquals(count, statuses.size());
            for (int i = 0; i < count; ++i)
                assertEquals(status, statuses.get(i));
            assertEquals(count, headers.get());
            assertEquals(count, ends.get());
        }
    }

    private static void parseResponse(ByteBuffer output, ClientParser.Listener listener)
    {
        assertTrue(output.hasRemaining(), "no response written");
        new ClientParser(listener).parse(output);
    }

    private static ByteBuffer generateRequest(int id)
    {
        return generateRequest(id, true);
    }

    private static ByteBuffer generateRequestHeaders(int id)
    {
        return generateRequest(id, false);
    }

    private static ByteBuffer generateRequest(int id, boolean complete)
    {
        ClientGenerator generator = new ClientGenerator(ByteBufferPool.NON_POOLING);
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        HttpFields.Mutable params = HttpFields.build()
            .put(FCGI.Headers.REQUEST_METHOD, "GET")
            .put(FCGI.Headers.DOCUMENT_URI, "/")
            .put(FCGI.Headers.QUERY_STRING, "")
            .put(FCGI.Headers.SERVER_PROTOCOL, HttpVersion.HTTP_1_1.asString());
        generator.generateRequestHeaders(accumulator, id, params);
        if (complete)
            generator.generateRequestContent(accumulator, id, BufferUtil.EMPTY_BUFFER, true);
        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        int capacity = (int)accumulator.getTotalLength();
        ByteBuffer request = ByteBuffer.allocate(capacity);
        buffers.forEach(request::put);
        accumulator.release();
        BufferUtil.flipToFlush(request, 0);
        return request;
    }

    private static void run(Runnable action, AtomicReference<Throwable> failure)
    {
        try
        {
            action.run();
        }
        catch (Throwable x)
        {
            failure.set(x);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String message)
    {
        try
        {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException(message);
        }
        catch (InterruptedException x)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException(x);
        }
    }
}
