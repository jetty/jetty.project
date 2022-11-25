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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
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
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
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
                return true;
            }
        });

        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(new BufferingResponseListener()
            {
                private final AtomicInteger chunks = new AtomicInteger();

                @Override
                public void onContent(Response response, Content.Chunk chunk, Runnable demander)
                {
                    chunk.release();
                    if (chunks.incrementAndGet() == 1)
                        contentLatch.countDown();
                    // Need to demand also after the second
                    // chunk to allow the parser to proceed
                    // and complete the response.
                    demander.run();
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
        startServer(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
            }
        });
        startClient(transport);
        client.stop();
        client.setByteBufferPool(new MappedByteBufferPool(bufferSize));
        client.setResponseBufferSize(bufferSize);
        client.start();

        Queue<Runnable> demandQueue = new ConcurrentLinkedQueue<>();
        Queue<Content.Chunk> contentQueue = new ConcurrentLinkedQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onContent(Response response, Content.Chunk chunk, Runnable demander)
                {
                    // Don't demand and don't succeed callbacks.
                    demandQueue.offer(demander);
                    contentQueue.offer(chunk);
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

        // Demand one more buffer.
        Runnable demand = demandQueue.poll();
        assertNotNull(demand);
        demand.run();
        // The client should have received just `count` more buffers.
        Thread.sleep(1000);
        assertEquals(1, demandQueue.size());
        assertEquals(2, contentQueue.size());

        // Demand all the rest.
        demand = demandQueue.poll();
        assertNotNull(demand);
        long begin = NanoTime.now();
        // Spin on demand until content.length bytes have been read.
        while (content.length > contentQueue.stream().map(Content.Chunk::getByteBuffer).mapToInt(Buffer::remaining).sum())
        {
            if (NanoTime.millisSince(begin) > 5000L)
                fail("Failed to demand all content");
            demand.run();
        }
        demand.run(); // Demand one last time to get EOF.
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));

        byte[] received = new byte[content.length];
        AtomicInteger offset = new AtomicInteger();
        contentQueue.forEach(buffer ->
        {
            int length = buffer.remaining();
            buffer.get(received, offset.getAndAdd(length), length);
        });
        assertArrayEquals(content, received);

        contentQueue.forEach(Content.Chunk::release);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testContentWhileStalling(Transport transport) throws Exception
    {
        CountDownLatch serverContentLatch = new CountDownLatch(1);
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
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
                return true;
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
    public void testTwoListenersWithDifferentDemand(Transport transport) throws Exception
    {
        int bufferSize = 1536;
        byte[] bytes = new byte[10 * bufferSize];
        new Random().nextBytes(bytes);
        startServer(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, bytes.length);
                response.write(true, ByteBuffer.wrap(bytes), callback);
                return true;
            }
        });
        startClient(transport);
        client.stop();
        client.setByteBufferPool(new MappedByteBufferPool(bufferSize));
        client.setResponseBufferSize(bufferSize);
        client.start();

        AtomicInteger listener1Chunks = new AtomicInteger();
        AtomicInteger listener1ContentSize = new AtomicInteger();
        AtomicReference<LongConsumer> listener1DemandRef = new AtomicReference<>();
        Response.DemandedContentListener listener1 = (response, demand, content, callback) ->
        {
            listener1Chunks.incrementAndGet();
            listener1ContentSize.addAndGet(content.remaining());
            callback.succeeded();
            listener1DemandRef.set(demand);
        };
        AtomicInteger listener2Chunks = new AtomicInteger();
        AtomicInteger listener2ContentSize = new AtomicInteger();
        AtomicReference<LongConsumer> listener2DemandRef = new AtomicReference<>();
        Response.DemandedContentListener listener2 = (response, demand, content, callback) ->
        {
            listener2Chunks.incrementAndGet();
            listener2ContentSize.addAndGet(content.remaining());
            callback.succeeded();
            listener2DemandRef.set(demand);
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

        // Make both listeners progress in locksteps.
        int i = 0;
        while (resultLatch.getCount() > 0)
        {
            i++;

            await().atMost(5, TimeUnit.SECONDS).until(listener1DemandRef::get, not(nullValue()));
            await().atMost(5, TimeUnit.SECONDS).until(listener2DemandRef::get, not(nullValue()));

            // Assert that no listener can progress for as long as both listeners did not demand.
            assertThat(listener1Chunks.get(), is(i));
            assertThat(listener2Chunks.get(), is(i));
            listener2DemandRef.getAndSet(null).accept(1);
            assertThat(listener1Chunks.get(), is(i));
            assertThat(listener2Chunks.get(), is(i));
            listener1DemandRef.getAndSet(null).accept(1);
        }

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        assertThat(listener1ContentSize.get(), is(bytes.length));
        assertThat(listener2ContentSize.get(), is(bytes.length));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testGZippedResponseContentWithAsyncDemand(Transport transport) throws Exception
    {
        int chunks = 64;
        byte[] content = new byte[chunks * 1024];
        new Random().nextBytes(content);

        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
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
                return true;
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
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, content.length);
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
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
