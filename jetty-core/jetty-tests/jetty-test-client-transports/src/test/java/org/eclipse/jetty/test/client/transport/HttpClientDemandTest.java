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
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.IteratingNestedCallback;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        Queue<Runnable> demanderQueue = new ConcurrentLinkedQueue<>();
        Queue<Content.Chunk> contentQueue = new ConcurrentLinkedQueue<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .send(new BufferingResponseListener()
            {
                @Override
                public void onContent(Response response, Content.Chunk chunk, Runnable demander)
                {
                    // Don't demand and don't release chunks.
                    contentQueue.offer(chunk);
                    demanderQueue.offer(demander);
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
        assertEquals(1, demanderQueue.size());
        assertEquals(1, contentQueue.size());

        // Demand one more buffer.
        Runnable demander = demanderQueue.poll();
        assertNotNull(demander);
        demander.run();
        // The client should have received just `count` more buffers.
        Thread.sleep(1000);
        assertEquals(1, demanderQueue.size());
        assertEquals(2, contentQueue.size());

        // Demand all the rest.
        demander = demanderQueue.poll();
        assertNotNull(demander);
        long begin = NanoTime.now();
        // Spin on demand until content.length bytes have been read.
        while (content.length > contentQueue.stream().map(Content.Chunk::getByteBuffer).mapToInt(Buffer::remaining).sum())
        {
            if (NanoTime.millisSince(begin) > 5000L)
                fail("Failed to demand all content");
            demander.run();
        }
        demander.run(); // Demand one last time to get EOF.
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
        AtomicReference<Runnable> demanderRef = new AtomicReference<>();
        AtomicReference<Content.Chunk> chunkRef = new AtomicReference<>();
        CountDownLatch clientContentLatch = new CountDownLatch(2);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentAsync((response, chunk, demander) ->
            {
                chunkRef.set(chunk);
                try
                {
                    if (demanderRef.getAndSet(demander) == null)
                    {
                        // Produce more content just before stalling.
                        serverContentLatch.countDown();
                        // Wait for the content to arrive to the client.
                        Thread.sleep(delay);
                    }
                    clientContentLatch.countDown();
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
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
        Content.Chunk c1 = chunkRef.getAndSet(null);
        assertThat(asStringAndRelease(c1), is("A"));

        // Now demand, we should be notified of the second chunk.
        demanderRef.get().run();
        assertTrue(clientContentLatch.await(5, TimeUnit.SECONDS));
        Content.Chunk c2 = chunkRef.getAndSet(null);
        assertThat(asStringAndRelease(c2), is("B"));

        // Demand once more to trigger response success.
        demanderRef.get().run();
        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));

        // Make sure the chunks were not leaked.
        assertThrows(IllegalStateException.class, c1::release);
        assertThrows(IllegalStateException.class, c2::release);
    }

    private static String asStringAndRelease(Content.Chunk chunk)
    {
        try
        {
            return BufferUtil.toString(chunk.getByteBuffer());
        }
        finally
        {
            chunk.release();
        }
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
        AtomicReference<Runnable> listener1DemanderRef = new AtomicReference<>();
        Response.AsyncContentListener listener1 = (response, chunk, demander) ->
        {
            listener1Chunks.incrementAndGet();
            listener1ContentSize.addAndGet(chunk.remaining());
            chunk.release();
            listener1DemanderRef.set(demander);
        };
        AtomicInteger listener2Chunks = new AtomicInteger();
        AtomicInteger listener2ContentSize = new AtomicInteger();
        AtomicReference<Runnable> listener2DemanderRef = new AtomicReference<>();
        Response.AsyncContentListener listener2 = (response, chunk, demander) ->
        {
            listener2Chunks.incrementAndGet();
            listener2ContentSize.addAndGet(chunk.remaining());
            chunk.release();
            listener2DemanderRef.set(demander);
        };

        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentAsync(listener1)
            .onResponseContentAsync(listener2)
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

            await().atMost(5, TimeUnit.SECONDS).until(listener1DemanderRef::get, not(nullValue()));
            await().atMost(5, TimeUnit.SECONDS).until(listener2DemanderRef::get, not(nullValue()));

            // Assert that no listener can progress for as long as both listeners did not demand.
            assertThat(listener1Chunks.get(), is(i));
            assertThat(listener2Chunks.get(), is(i));
            listener2DemanderRef.getAndSet(null).run();
            assertThat(listener1Chunks.get(), is(i));
            assertThat(listener2Chunks.get(), is(i));
            listener1DemanderRef.getAndSet(null).run();
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
            .onResponseContentAsync((response, chunk, demander) ->
            {
                received.put(chunk.getByteBuffer());
                chunk.release();
                if (!chunk.isTerminal())
                    new Thread(demander).start();
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
        AtomicReference<Runnable> beforeContentDemanderRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentSource(new Response.ContentSourceListener()
            {
                @Override
                public void onContentSource(Response response, Content.Source contentSource)
                {
                    Runnable demander = () -> contentSource.demand(() -> onContentSource(response, contentSource));
                    if (beforeContentDemanderRef.getAndSet(demander) == null)
                    {
                        // 1st time, do not demand now.
                        beforeContentLatch.countDown();
                        return;
                    }

                    Content.Chunk chunk = contentSource.read();
                    if (chunk == null)
                    {
                        demander.run();
                        return;
                    }
                    if (chunk.isTerminal())
                    {
                        chunk.release();
                        return;
                    }

                    contentLatch.countDown();
                    received.put(chunk.getByteBuffer());
                    chunk.release();
                    demander.run();
                }
            })
            .send(result ->
            {
                Assertions.assertTrue(result.isSucceeded());
                Assertions.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));
        Runnable demander = beforeContentDemanderRef.get();

        // Content must not be notified until we demand.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        demander.run();

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(content, bytes);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDelayedBeforeContentDemandWithNoResponseContent(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());

        AtomicReference<Runnable> beforeContentDemanderRef = new AtomicReference<>();
        CountDownLatch beforeContentLatch = new CountDownLatch(1);
        CountDownLatch contentLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentSource(new Response.ContentSourceListener()
            {
                @Override
                public void onContentSource(Response response, Content.Source contentSource)
                {
                    Runnable demander = () -> contentSource.demand(() -> onContentSource(response, contentSource));
                    if (beforeContentDemanderRef.getAndSet(demander) == null)
                    {
                        // 1st time, do not demand now.
                        beforeContentLatch.countDown();
                        return;
                    }

                    Content.Chunk chunk = contentSource.read();
                    if (chunk == null)
                    {
                        demander.run();
                        return;
                    }
                    if (chunk.isTerminal())
                    {
                        chunk.release();
                        return;
                    }

                    contentLatch.countDown();
                    chunk.release();
                    demander.run();
                }
            })
            .send(result ->
            {
                Assertions.assertTrue(result.isSucceeded());
                Assertions.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(beforeContentLatch.await(5, TimeUnit.SECONDS));
        Runnable demander = beforeContentDemanderRef.get();

        // Content must not be notified until we demand.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        demander.run();

        // Content must not be notified as there is no content.
        assertFalse(contentLatch.await(1, TimeUnit.SECONDS));

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReadDemandInSpawnedThread(Transport transport) throws Exception
    {
        int totalBytes = 1024;
        start(transport, new TestProcessor(totalBytes));

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .onResponseContentSource((response, contentSource) -> contentSource.demand(() -> new Thread(new Accumulator(contentSource, chunks)).start()))
            .send(result ->
            {
                Assertions.assertTrue(result.isSucceeded());
                Assertions.assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));

        Content.Chunk lastChunk = chunks.get(chunks.size() - 1);
        assertThat(lastChunk.isLast(), is(true));
        int accumulatedSize = chunks.stream().mapToInt(chunk ->
        {
            int remaining = chunk.remaining();
            chunk.release();
            return remaining;
        }).sum();
        assertThat(accumulatedSize, is(totalBytes));
    }

    private static class Accumulator implements Runnable
    {
        private final Content.Source contentSource;
        private final List<Content.Chunk> chunks;

        private Accumulator(Content.Source contentSource, List<Content.Chunk> chunks)
        {
            this.contentSource = contentSource;
            this.chunks = chunks;
        }

        @Override
        public void run()
        {
            Content.Chunk chunk = contentSource.read();
            if (chunk == null)
            {
                contentSource.demand(this);
                return;
            }
            chunks.add(chunk);
            if (!chunk.isLast())
                contentSource.demand(this);
        }
    }

    private static class TestProcessor extends Handler.Processor
    {
        private final int totalBytes;

        private TestProcessor(int totalBytes)
        {
            this.totalBytes = totalBytes;
        }

        @Override
        public void process(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

            IteratingCallback iteratingCallback = new IteratingNestedCallback(callback)
            {
                int count = 0;
                @Override
                protected Action process()
                {
                    boolean last = ++count == totalBytes;
                    if (count > totalBytes)
                        return Action.SUCCEEDED;
                    response.write(last, ByteBuffer.wrap(new byte[1]), this);
                    return Action.SCHEDULED;
                }
            };
            iteratingCallback.iterate();
        }
    }
}
