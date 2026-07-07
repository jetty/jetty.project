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

package org.eclipse.jetty.http3.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3Stream;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataDemandTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testOnDataAvailableThenExit(TransportType transportType) throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        if (serverStreamRef.compareAndSet(null, stream))
                        {
                            // Do nothing on the first pass, with respect to demand and reading data.
                            serverStreamLatch.countDown();
                        }
                        else
                        {
                            // When resumed, demand all content until the last.
                            Content.Chunk chunk = stream.read();
                            if (chunk != null)
                            {
                                chunk.release();
                                if (chunk.isLast())
                                {
                                    serverDataLatch.countDown();
                                    return;
                                }
                            }
                            stream.demand();
                        }
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));
        stream.data(new DataFrame(ReadableBuffer.allocate(8192, false), true), Promise.Invocable.noop());

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));
        // Wait a little to be sure we do not spin.
        Thread.sleep(500);
        assertEquals(1, onDataAvailableCalls.get());

        // Resume processing of data, this should call onDataAvailable().
        serverStreamRef.get().demand();

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testOnDataAvailableThenReadDataThenExit(TransportType transportType) throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch firstDataLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set(stream);
                        stream.demand();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();

                        if (firstDataLatch.getCount() > 0)
                        {
                            // Read only one chunk of data.
                            Content.Chunk chunk = stream.read();
                            if (chunk == null)
                            {
                                stream.demand();
                                return;
                            }
                            chunk.release();
                            firstDataLatch.countDown();
                            // Don't demand, just exit.
                            return;
                        }

                        // When resumed, demand all content until the last.
                        Content.Chunk chunk = stream.read();
                        if (chunk != null)
                        {
                            chunk.release();
                            if (chunk.isLast())
                            {
                                serverDataLatch.countDown();
                                return;
                            }
                        }
                        stream.demand();
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));
        stream.data(new DataFrame(ReadableBuffer.allocate(16, false), false), Promise.Invocable.noop());

        assertTrue(firstDataLatch.await(5, TimeUnit.SECONDS));
        // Wait a little to be sure we do not spin.
        Thread.sleep(500);
        // Account for a spurious initial read of a null chunk.
        long oDACalls = onDataAvailableCalls.get();
        assertThat(oDACalls, Matchers.lessThanOrEqualTo(2L));

        // Resume processing of data, this should call onDataAvailable(), but there is no data to read yet.
        Stream serverStream = serverStreamRef.get();
        serverStream.demand();

        await().atMost(1, TimeUnit.SECONDS).until(() -> (onDataAvailableCalls.get() == oDACalls + 1) && ((HTTP3Stream)serverStream).hasDemand());

        stream.data(new DataFrame(ReadableBuffer.allocate(32, false), true), Promise.Invocable.noop());

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testOnDataAvailableThenReadDataNullThenExit(TransportType transportType) throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        if (serverStreamRef.compareAndSet(null, stream))
                        {
                            while (true)
                            {
                                Content.Chunk chunk = stream.read();
                                if (chunk == null)
                                {
                                    serverStreamLatch.countDown();
                                    // Do not demand after reading null data.
                                    return;
                                }
                                else
                                {
                                    chunk.release();
                                }
                            }
                        }
                        else
                        {
                            // When resumed, demand all content until the last.
                            Content.Chunk chunk = stream.read();
                            if (chunk != null)
                            {
                                chunk.release();
                                if (chunk.isLast())
                                {
                                    serverDataLatch.countDown();
                                    return;
                                }
                            }
                            stream.demand();
                        }
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));
        stream.data(new DataFrame(ReadableBuffer.allocate(16, false), false), Promise.Invocable.noop());

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));
        // Wait a little to be sure we do not spin.
        Thread.sleep(500);
        assertEquals(1, onDataAvailableCalls.get());

        // Send a last empty frame.
        stream.data(new DataFrame(ReadableBuffer.EMPTY, true), Promise.Invocable.noop());

        // Resume processing of data, this should call onDataAvailable().
        serverStreamRef.get().demand();

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHeadersNoDataThenTrailers(TransportType transportType) throws Exception
    {
        CountDownLatch serverTrailerLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onTrailer(Stream.Server stream, HeadersFrame frame)
                    {
                        serverTrailerLatch.countDown();
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));
        Blocker.<Stream>blockWithPromise(5, TimeUnit.SECONDS, p -> stream.trailer(new HeadersFrame(new MetaData(HttpVersion.HTTP_3, HttpFields.EMPTY), true), p));

        assertTrue(serverTrailerLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testHeadersDataTrailers(TransportType transportType) throws Exception
    {
        int dataLength = 8192;
        AtomicInteger dataRead = new AtomicInteger();
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        CountDownLatch serverTrailerLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                            return;
                        }
                        if (dataRead.addAndGet(chunk.getByteBuffer().remaining()) == dataLength)
                            serverDataLatch.countDown();
                        chunk.release();
                        if (!chunk.isLast())
                            stream.demand();
                    }

                    @Override
                    public void onTrailer(Stream.Server stream, HeadersFrame frame)
                    {
                        serverTrailerLatch.countDown();
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));
        Blocker.<Stream>blockWithPromise(5, TimeUnit.SECONDS, p -> stream.data(new DataFrame(ReadableBuffer.allocate(dataLength, false), false), p));

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        long calls = onDataAvailableCalls.get();

        Blocker.<Stream>blockWithPromise(5, TimeUnit.SECONDS, p -> stream.trailer(new HeadersFrame(new MetaData(HttpVersion.HTTP_3, HttpFields.EMPTY), true), p));

        assertTrue(serverTrailerLatch.await(5, TimeUnit.SECONDS));
        // In order to detect that the trailer have arrived, we must call
        // onDataAvailable() one more time, possibly two more times if an
        // empty DATA frame was delivered to indicate the end of the stream.
        assertThat(onDataAvailableCalls.get(), Matchers.lessThanOrEqualTo(calls + 2));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRetainRelease(TransportType transportType) throws Exception
    {
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        List<Content.Chunk> chunks = new ArrayList<>();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        while (true)
                        {
                            Content.Chunk chunk = stream.read();
                            if (chunk == null)
                            {
                                stream.demand();
                                return;
                            }
                            // Store the chunk away to be used later.
                            chunks.add(chunk);
                            if (chunk.isLast())
                            {
                                serverDataLatch.countDown();
                                return;
                            }
                        }
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));

        byte[] bytesSent = new byte[16384];
        new Random().nextBytes(bytesSent);
        stream.data(new DataFrame(ReadableBuffer.wrap(bytesSent), true), Promise.Invocable.noop());

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));

        assertEquals(bytesSent.length, chunks.stream().mapToInt(d -> d.getByteBuffer().remaining()).sum());
        byte[] bytesReceived = new byte[bytesSent.length];
        ByteBuffer buffer = ByteBuffer.wrap(bytesReceived);
        chunks.forEach(d -> buffer.put(d.getByteBuffer()));
        assertArrayEquals(bytesSent, bytesReceived);
        chunks.forEach(Content.Chunk::release);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDisableDemandOnRequest(TransportType transportType) throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        serverStreamRef.set(stream);
                        serverRequestLatch.countDown();
                        // Do not demand here.
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        Content.Chunk chunk = stream.read();
                        if (chunk != null)
                        {
                            chunk.release();
                            if (chunk.isLast())
                            {
                                serverDataLatch.countDown();
                                return;
                            }
                        }
                        stream.demand();
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));

        stream.data(new DataFrame(ReadableBuffer.allocate(4096, false), true), Promise.Invocable.noop());

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));

        // Wait a little to verify that onDataAvailable() is not called.
        Thread.sleep(500);
        assertEquals(0, onDataAvailableCalls.get());

        // Resume processing of data.
        serverStreamRef.get().demand();

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testBlockingReadInADifferentThread(TransportType transportType) throws Exception
    {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    private final Semaphore semaphore = new Semaphore(0);

                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        stream.demand();

                        // Simulate a thread dispatched to read the request content with blocking I/O.
                        new Thread(() ->
                        {
                            try
                            {
                                // Wait for onDataAvailable() to be called before start reading.
                                semaphore.acquire();
                                while (true)
                                {
                                    Content.Chunk chunk = stream.read();
                                    if (chunk != null)
                                    {
                                        // Consume the chunk.
                                        chunk.release();
                                        if (chunk.isLast())
                                        {
                                            dataLatch.countDown();
                                            return;
                                        }
                                    }
                                    else
                                    {
                                        // Demand and block.
                                        stream.demand();
                                        blockLatch.countDown();
                                        semaphore.acquire();
                                    }
                                }
                            }
                            catch (InterruptedException x)
                            {
                                x.printStackTrace();
                            }
                        }).start();
                    }

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        semaphore.release();
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));

        // Send a first chunk of data.
        try (Blocker.Promise<Stream> promise = Blocker.promise())
        {
            stream.data(new DataFrame(ReadableBuffer.allocate(16 * 1024, false), false), promise);
            // Wait some time until the server reads no data after the first chunk.
            assertTrue(blockLatch.await(5, TimeUnit.SECONDS));
            promise.block(5, TimeUnit.SECONDS);
        }

        // Send the last chunk of data.
        stream.data(new DataFrame(ReadableBuffer.allocate(32 * 1024, false), true), Promise.Invocable.noop());

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testReadDataIdempotent(TransportType transportType) throws Exception
    {
        CountDownLatch nullDataLatch = new CountDownLatch(1);
        CountDownLatch lastDataLatch = new CountDownLatch(1);
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    private boolean firstData;
                    private boolean nullData;

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        while (!firstData)
                        {
                            Content.Chunk chunk = stream.read();
                            if (chunk == null)
                            {
                                stream.demand();
                                return;
                            }
                            firstData = true;
                            chunk.release();
                        }

                        if (!nullData)
                        {
                            assertNull(stream.read());
                            // Verify that read() is idempotent.
                            assertNull(stream.read());
                            nullData = true;
                            nullDataLatch.countDown();
                            stream.demand();
                            return;
                        }

                        Content.Chunk chunk = stream.read();
                        if (chunk == null)
                        {
                            stream.demand();
                        }
                        else
                        {
                            chunk.release();
                            if (chunk.isLast())
                                lastDataLatch.countDown();
                            else
                                stream.demand();
                        }
                    }
                };
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(request, new Stream.Client.Listener() {}, p));

        // Send a first chunk to trigger reads.
        stream.data(new DataFrame(ReadableBuffer.allocate(16, false), false), Promise.Invocable.noop());

        assertTrue(nullDataLatch.await(5, TimeUnit.SECONDS));

        stream.data(new DataFrame(ReadableBuffer.allocate(4096, false), true), Promise.Invocable.noop());

        assertTrue(lastDataLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testDemandAfterEOF(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, "hello", callback);
                return true;
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        CountDownLatch latch = new CountDownLatch(1);
        HeadersFrame request = new HeadersFrame(newRequest("/"), false);
        session.newRequest(request, new Stream.Client.Listener()
        {
            private int dataCalls;

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                Content.Chunk chunk = stream.read();
                if (chunk == null && dataCalls == 0)
                {
                    stream.demand();
                    return;
                }

                if (++dataCalls == 1)
                {
                    String content = StandardCharsets.UTF_8.decode(chunk.getByteBuffer()).toString();
                    assertEquals("hello", content);
                    assertTrue(chunk.isLast());
                    chunk.release();
                    // Demand one more time, we should get an EOF.
                    stream.demand();
                }
                else
                {
                    assertNotNull(chunk);
                    assertTrue(chunk.isLast());
                    assertEquals(0, chunk.getByteBuffer().remaining());
                    chunk.release();
                    latch.countDown();
                }
            }
        }, Promise.Invocable.noop());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testResponseContentDemandedAfterDelay(TransportType transportType) throws Exception
    {
        start(transportType, new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Session.Server session, HeadersFrame frame)
            {
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onRequest(Stream.Server stream, HeadersFrame frame)
                    {
                        // Send the response.
                        HeadersFrame responseFrame = new HeadersFrame(new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_3, HttpFields.EMPTY), false);
                        stream.respond(responseFrame, new Promise.Invocable.NonBlocking<>()
                        {
                            @Override
                            public void succeeded(Stream result)
                            {
                                result.data(new DataFrame(ReadableBuffer.allocate(1024, false), true), Promise.Invocable.noop());
                            }
                        });
                    }
                };
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener() {});

        AtomicReference<Stream.Client> streamRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientSession.newRequest(new HeadersFrame(newRequest("/"), false), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                // Do not demand now.
                streamRef.set(stream);
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                while (true)
                {
                    Content.Chunk chunk = stream.read();
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }
                    chunk.release();
                    if (chunk.isLast())
                    {
                        latch.countDown();
                        return;
                    }
                }
            }
        }, Promise.Invocable.noop());

        Stream.Client stream = await().atMost(5, TimeUnit.SECONDS).until(streamRef::get, Objects::nonNull);

        // Delay the demand until the response has arrived from the server.
        Thread.sleep(1000);
        stream.demand();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
