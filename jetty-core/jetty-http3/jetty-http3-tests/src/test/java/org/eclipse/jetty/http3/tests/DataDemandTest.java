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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataDemandTest extends AbstractClientServerTest
{
    @Test
    public void testOnDataAvailableThenExit() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
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
                            Stream.Data data = stream.readData();
                            if (data != null)
                            {
                                data.release();
                                if (data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(ByteBuffer.allocate(8192), true));

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));
        // Wait a little to be sure we do not spin.
        Thread.sleep(500);
        assertEquals(1, onDataAvailableCalls.get());

        // Resume processing of data, this should call onDataAvailable().
        serverStreamRef.get().demand();

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOnDataAvailableThenReadDataThenExit() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        if (serverStreamRef.compareAndSet(null, stream))
                        {
                            // Read only one chunk of data.
                            await().atMost(1, TimeUnit.SECONDS).until(() ->
                            {
                                Stream.Data data = stream.readData();
                                if (data == null)
                                    return false;
                                data.release();
                                return true;
                            });
                            serverStreamLatch.countDown();
                            // Don't demand, just exit.
                        }
                        else
                        {
                            // When resumed, demand all content until the last.
                            Stream.Data data = stream.readData();
                            if (data != null)
                            {
                                data.release();
                                if (data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(ByteBuffer.allocate(16), false));

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));
        // Wait a little to be sure we do not spin.
        Thread.sleep(500);
        assertEquals(1, onDataAvailableCalls.get());

        // Resume processing of data, this should call onDataAvailable(), but there is no data to read yet.
        Stream serverStream = serverStreamRef.get();
        serverStream.demand();

        await().atMost(1, TimeUnit.SECONDS).until(() -> onDataAvailableCalls.get() == 2 && ((HTTP3Stream)serverStream).hasDemand());

        stream.data(new DataFrame(ByteBuffer.allocate(32), true));

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testOnDataAvailableThenReadDataNullThenExit() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverStreamLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
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
                                Stream.Data data = stream.readData();
                                if (data == null)
                                {
                                    serverStreamLatch.countDown();
                                    break;
                                }
                                else
                                {
                                    data.release();
                                }
                            }
                            // Do not demand after reading null data.
                        }
                        else
                        {
                            // When resumed, demand all content until the last.
                            Stream.Data data = stream.readData();
                            if (data != null)
                            {
                                data.release();
                                if (data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(ByteBuffer.allocate(16), false));

        assertTrue(serverStreamLatch.await(5, TimeUnit.SECONDS));
        // Wait a little to be sure we do not spin.
        Thread.sleep(500);
        assertEquals(1, onDataAvailableCalls.get());

        // Send a last empty frame.
        stream.data(new DataFrame(BufferUtil.EMPTY_BUFFER, true));

        // Resume processing of data, this should call onDataAvailable().
        serverStreamRef.get().demand();

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHeadersNoDataThenTrailers() throws Exception
    {
        CountDownLatch serverTrailerLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);
        stream.trailer(new HeadersFrame(new MetaData(HttpVersion.HTTP_3, HttpFields.EMPTY), true)).get(5, TimeUnit.SECONDS);

        assertTrue(serverTrailerLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHeadersDataTrailers() throws Exception
    {
        int dataLength = 8192;
        AtomicInteger dataRead = new AtomicInteger();
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        CountDownLatch serverTrailerLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        Stream.Data data = stream.readData();
                        if (data == null)
                        {
                            stream.demand();
                            return;
                        }
                        if (dataRead.addAndGet(data.getByteBuffer().remaining()) == dataLength)
                            serverDataLatch.countDown();
                        data.release();
                        if (!data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);

        stream.data(new DataFrame(ByteBuffer.allocate(dataLength), false));

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        long calls = onDataAvailableCalls.get();

        stream.trailer(new HeadersFrame(new MetaData(HttpVersion.HTTP_3, HttpFields.EMPTY), true)).get(5, TimeUnit.SECONDS);

        assertTrue(serverTrailerLatch.await(5, TimeUnit.SECONDS));
        // In order to detect that the trailer have arrived, we must call
        // onDataAvailable() one more time, possibly two more times if an
        // empty DATA frame was delivered to indicate the end of the stream.
        assertThat(onDataAvailableCalls.get(), Matchers.lessThanOrEqualTo(calls + 2));
    }

    @Test
    public void testRetainRelease() throws Exception
    {
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        List<Stream.Data> datas = new ArrayList<>();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        while (true)
                        {
                            Stream.Data data = stream.readData();
                            if (data == null)
                            {
                                stream.demand();
                                return;
                            }
                            // Store the Data away to be used later.
                            datas.add(data);
                            if (data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);

        byte[] bytesSent = new byte[16384];
        new Random().nextBytes(bytesSent);
        stream.data(new DataFrame(ByteBuffer.wrap(bytesSent), true));

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));

        assertEquals(bytesSent.length, datas.stream().mapToInt(d -> d.getByteBuffer().remaining()).sum());
        byte[] bytesReceived = new byte[bytesSent.length];
        ByteBuffer buffer = ByteBuffer.wrap(bytesReceived);
        datas.forEach(d -> buffer.put(d.getByteBuffer()));
        assertArrayEquals(bytesSent, bytesReceived);
        datas.forEach(Stream.Data::release);
    }

    @Test
    public void testDisableDemandOnRequest() throws Exception
    {
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        CountDownLatch serverDataLatch = new CountDownLatch(1);
        AtomicLong onDataAvailableCalls = new AtomicLong();
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                serverRequestLatch.countDown();
                // Do not demand here.
                return new Stream.Server.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        onDataAvailableCalls.incrementAndGet();
                        Stream.Data data = stream.readData();
                        if (data != null)
                        {
                            data.release();
                            if (data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);

        stream.data(new DataFrame(ByteBuffer.allocate(4096), true));

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));

        // Wait a little to verify that onDataAvailable() is not called.
        Thread.sleep(500);
        assertEquals(0, onDataAvailableCalls.get());

        // Resume processing of data.
        serverStreamRef.get().demand();

        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingReadInADifferentThread() throws Exception
    {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();

                // Simulate a thread dispatched to read the request content with blocking I/O.
                Semaphore semaphore = new Semaphore(0);
                new Thread(() ->
                {
                    try
                    {
                        // Wait for onDataAvailable() to be called before start reading.
                        semaphore.acquire();
                        while (true)
                        {
                            Stream.Data data = stream.readData();
                            if (data != null)
                            {
                                // Consume the data.
                                data.release();
                                if (data.isLast())
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

                return new Stream.Server.Listener()
                {
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {}).get(5, TimeUnit.SECONDS);

        // Send a first chunk of data.
        stream.data(new DataFrame(ByteBuffer.allocate(16 * 1024), false));

        // Wait some time until the server reads no data after the first chunk.
        assertTrue(blockLatch.await(5, TimeUnit.SECONDS));

        // Send the last chunk of data.
        stream.data(new DataFrame(ByteBuffer.allocate(32 * 1024), true));

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testReadDataIdempotent() throws Exception
    {
        CountDownLatch nullDataLatch = new CountDownLatch(1);
        CountDownLatch lastDataLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public Stream.Server.Listener onRequest(Stream.Server stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Server.Listener()
                {
                    private boolean firstData;
                    private boolean nullData;

                    @Override
                    public void onDataAvailable(Stream.Server stream)
                    {
                        while (!firstData)
                        {
                            Stream.Data data = stream.readData();
                            if (data == null)
                                continue;
                            firstData = true;
                            data.release();
                            break;
                        }

                        if (!nullData)
                        {
                            assertNull(stream.readData());
                            // Verify that readData() is idempotent.
                            assertNull(stream.readData());
                            nullData = true;
                            nullDataLatch.countDown();
                            stream.demand();
                            return;
                        }

                        Stream.Data data = stream.readData();
                        if (data == null)
                        {
                            stream.demand();
                        }
                        else
                        {
                            data.release();
                            if (data.isLast())
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
        Stream stream = session.newRequest(request, new Stream.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        // Send a first chunk to trigger reads.
        stream.data(new DataFrame(ByteBuffer.allocate(16), false));

        assertTrue(nullDataLatch.await(5, TimeUnit.SECONDS));

        stream.data(new DataFrame(ByteBuffer.allocate(4096), true));

        assertTrue(lastDataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDemandAfterEOF() throws Exception
    {
        start(new Handler.Abstract()
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
                Stream.Data data = stream.readData();
                if (data == null && dataCalls == 0)
                {
                    stream.demand();
                    return;
                }

                if (++dataCalls == 1)
                {
                    String content = StandardCharsets.UTF_8.decode(data.getByteBuffer()).toString();
                    assertEquals("hello", content);
                    assertTrue(data.isLast());
                    data.release();
                    // Demand one more time, we should get an EOF.
                    stream.demand();
                }
                else
                {
                    assertNotNull(data);
                    assertTrue(data.isLast());
                    assertEquals(0, data.getByteBuffer().remaining());
                    data.release();
                    latch.countDown();
                }
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
