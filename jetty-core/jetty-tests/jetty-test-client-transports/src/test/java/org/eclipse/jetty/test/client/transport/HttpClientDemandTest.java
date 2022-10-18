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

package org.eclipse.jetty.test.client.transport;

import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientDemandTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testDemandInTwoChunks(Transport transport) throws Exception
    {
        // Tests a special case where the first chunk is automatically
        // delivered, and the second chunk is explicitly demanded and
        // completes the response content.
        CountDownLatch contentLatch = new CountDownLatch(1);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                try
                {
                    response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 2);
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{'A'}));
                    contentLatch.await();
                    response.write(true, ByteBuffer.wrap(new byte[]{'B'}), callback);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(new BufferingResponseListener()
            {
                private final AtomicInteger chunks = new AtomicInteger();

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
                {
                    callback.succeeded();
                    if (chunks.incrementAndGet() == 1)
                        contentLatch.countDown();
                    // Need to demand also after the second
                    // chunk to allow the parser to proceed
                    // and complete the response.
                    demand.accept(1);
                }

                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    Response response = result.getResponse();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    resultLatch.countDown();
                }
            });

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDemand(Transport transport) throws Exception
    {
        // A small buffer size so the response content is
        // read in multiple buffers, but big enough for HTTP/3.
        int bufferSize = 1536;
        byte[] content = new byte[10 * bufferSize];
        new Random().nextBytes(content);
        startServer(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
            }
        });
        startClient(transport);
        client.stop();
        client.setByteBufferPool(new MappedByteBufferPool(bufferSize));
        client.setResponseBufferSize(bufferSize);
        client.start();

        Queue<LongConsumer> demandQueue = new ConcurrentLinkedQueue<>();
        Queue<ByteBuffer> contentQueue = new ConcurrentLinkedQueue<>();
        Queue<Callback> callbackQueue = new ConcurrentLinkedQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback)
                {
                    // Don't demand and don't succeed callbacks.
                    demandQueue.offer(demand);
                    contentQueue.offer(content);
                    callbackQueue.offer(callback);
                }

                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    Response response = result.getResponse();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    resultLatch.countDown();
                }
            });

        // Wait for the client to receive data from the server.
        // Wait a bit more to be sure it only receives 1 buffer.
        Thread.sleep(1000);
        assertEquals(1, demandQueue.size());
        assertEquals(1, contentQueue.size());
        assertEquals(1, callbackQueue.size());

        // Demand more buffers.
        int count = 2;
        LongConsumer demand = demandQueue.poll();
        assertNotNull(demand);
        demand.accept(count);
        // The client should have received just `count` more buffers.
        Thread.sleep(1000);
        assertEquals(count, demandQueue.size());
        assertEquals(1 + count, contentQueue.size());
        assertEquals(1 + count, callbackQueue.size());

        // Demand all the rest.
        demand = demandQueue.poll();
        assertNotNull(demand);
        demand.accept(Long.MAX_VALUE);
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));

        byte[] received = new byte[content.length];
        AtomicInteger offset = new AtomicInteger();
        contentQueue.forEach(buffer ->
        {
            int length = buffer.remaining();
            buffer.get(received, offset.getAndAdd(length), length);
        });
        assertArrayEquals(content, received);

        callbackQueue.forEach(Callback::succeeded);
    }

    @ParameterizedTest
    @MethodSource("transports")
    @Disabled("This test has two listeners, that's not supported anymore, API needs to be reworked to avoid this")
    public void testContentWhileStalling(Transport transport) throws Exception
    {
        CountDownLatch serverContentLatch = new CountDownLatch(1);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                try
                {
                    response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, 2);
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{'A'}));
                    serverContentLatch.await();
                    response.write(true, ByteBuffer.wrap(new byte[]{'B'}), callback);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
            }
        });

        long delay = 1000;
        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        CountDownLatch clientContentLatch = new CountDownLatch(2);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentDemanded((response, demand, content, callback) ->
            {
                try
                {
                    if (demandRef.getAndSet(demand) == null)
                    {
                        // Produce more content just before stalling.
                        serverContentLatch.countDown();
                        // Wait for the content to arrive to the client.
                        Thread.sleep(delay);
                    }
                    clientContentLatch.countDown();
                    // Succeed the callback but don't demand.
                    callback.succeeded();
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                Assertions.assertFalse(result.isFailed(), String.valueOf(result.getFailure()));
                Response response = result.getResponse();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                resultLatch.countDown();
            });

        // We did not demand, so we only expect one chunk of content.
        assertFalse(clientContentLatch.await(2 * delay, TimeUnit.MILLISECONDS));
        assertEquals(1, clientContentLatch.getCount());

        // Now demand, we should be notified of the second chunk.
        demandRef.get().accept(1);

        assertTrue(clientContentLatch.await(5, TimeUnit.SECONDS));

        // Demand once more to trigger response success.
        demandRef.get().accept(1);

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    @Disabled("This test has two listeners, that's not supported anymore, API needs to be reworked to avoid this")
    public void testTwoListenersWithDifferentDemand(Transport transport) throws Exception
    {
        int bufferSize = 1536;
        byte[] content = new byte[10 * bufferSize];
        new Random().nextBytes(content);
        startServer(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
            }
        });
        startClient(transport);
        client.stop();
        client.setByteBufferPool(new MappedByteBufferPool(bufferSize));
        client.setResponseBufferSize(bufferSize);
        client.start();

        AtomicInteger chunks = new AtomicInteger();
        Response.DemandedContentListener listener1 = (response, demand, content1, callback) ->
        {
            callback.succeeded();
            // The first time, demand infinitely.
            if (chunks.incrementAndGet() == 1)
                demand.accept(Long.MAX_VALUE);
        };
        BlockingQueue<ByteBuffer> contentQueue = new LinkedBlockingQueue<>();
        AtomicReference<LongConsumer> demandRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> demandLatch = new AtomicReference<>(new CountDownLatch(1));
        Response.DemandedContentListener listener2 = (response, demand, content12, callback) ->
        {
            assertTrue(contentQueue.offer(content12));
            demandRef.set(demand);
            demandLatch.get().countDown();
        };

        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentDemanded(listener1)
            .onResponseContentDemanded(listener2)
            .send(result ->
            {
                Assertions.assertFalse(result.isFailed(), String.valueOf(result.getFailure()));
                Response response = result.getResponse();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                resultLatch.countDown();
            });

        assertTrue(demandLatch.get().await(5, TimeUnit.SECONDS));
        LongConsumer demand = demandRef.get();
        assertNotNull(demand);
        assertEquals(1, contentQueue.size());
        assertNotNull(contentQueue.poll());

        // Must not get additional content because listener2 did not demand.
        assertNull(contentQueue.poll(1, TimeUnit.SECONDS));

        // Now demand, we should get content in both listeners.
        demandLatch.set(new CountDownLatch(1));
        demand.accept(1);

        assertNotNull(contentQueue.poll(5, TimeUnit.SECONDS));
        assertEquals(2, chunks.get());

        // Demand the rest and verify the result.
        assertTrue(demandLatch.get().await(5, TimeUnit.SECONDS));
        demand = demandRef.get();
        assertNotNull(demand);
        demand.accept(Long.MAX_VALUE);
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testGZippedResponseContentWithAsyncDemand(Transport transport) throws Exception
    {
        int chunks = 64;
        byte[] content = new byte[chunks * 1024];
        new Random().nextBytes(content);

        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, HttpHeaderValue.GZIP);
                try (GZIPOutputStream gzip = new GZIPOutputStream(Content.Sink.asOutputStream(response)))
                {
                    for (int i = 0; i < chunks; ++i)
                    {
                        Thread.sleep(10);
                        gzip.write(content, i * 1024, 1024);
                    }
                }
                callback.succeeded();
            }
        });

        byte[] bytes = new byte[content.length];
        ByteBuffer received = ByteBuffer.wrap(bytes);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentDemanded((response, demand, buffer, callback) ->
            {
                received.put(buffer);
                callback.succeeded();
                new Thread(() -> demand.accept(1)).start();
            })
            .send(result ->
            {
                Assertions.assertTrue(result.isSucceeded());
                Assertions.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(content, bytes);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDelayedBeforeContentDemand(Transport transport) throws Exception
    {
        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        start(transport, new Handler.Processor()
        {
            @Override
            public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
            }
        });

        byte[] bytes = new byte[content.length];
        ByteBuffer received = ByteBuffer.wrap(bytes);
        AtomicReference<LongConsumer> beforeContentDemandRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Do not demand now.
                    beforeContentDemandRef.set(demand);
                    beforeContentLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer buffer, Callback callback)
                {
                    contentLatch.countDown();
                    received.put(buffer);
                    callback.succeeded();
                    demand.accept(1);
                }
            })
            .send(result ->
            {
                Assertions.assertTrue(result.isSucceeded());
                Assertions.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));
        LongConsumer demand = beforeContentDemandRef.get();

        // Content must not be notified until we demand.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        demand.accept(1);

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(content, bytes);
    }

    @ParameterizedTest
    @MethodSource("transports")
    @Disabled("This test has two listeners, that's not supported anymore, API needs to be reworked to avoid this")
    public void testDelayedBeforeContentDemandWithNoResponseContent(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());

        AtomicReference<LongConsumer> beforeContentDemandRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentDemanded(new Response.DemandedContentListener()
            {
                @Override
                public void onBeforeContent(Response response, LongConsumer demand)
                {
                    // Do not demand now.
                    beforeContentDemandRef.set(demand);
                    beforeContentLatch.countDown();
                }

                @Override
                public void onContent(Response response, LongConsumer demand, ByteBuffer buffer, Callback callback)
                {
                    contentLatch.countDown();
                    callback.succeeded();
                    demand.accept(1);
                }
            })
            .send(result ->
            {
                Assertions.assertTrue(result.isSucceeded());
                Assertions.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));
        LongConsumer demand = beforeContentDemandRef.get();

        // Content must not be notified until we demand.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        demand.accept(1);

        // Content must not be notified as there is no content.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }
}
