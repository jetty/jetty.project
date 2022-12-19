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

package org.eclipse.jetty.http2.tests;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Flusher;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.http2.internal.generator.Generator;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamResetTest extends AbstractTest
{
    @Test
    public void testStreamSendingResetIsRemoved() throws Exception
    {
        start(new ServerSessionListener() {});

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code);
        FutureCallback resetCallback = new FutureCallback();
        stream.reset(resetFrame, resetCallback);
        resetCallback.get(5, TimeUnit.SECONDS);
        // After reset the stream should be gone.
        assertEquals(0, client.getStreams().size());
    }

    @Test
    public void testStreamReceivingResetIsRemoved() throws Exception
    {
        final AtomicReference<Stream> streamRef = new AtomicReference<>();
        final CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame, Callback callback)
                    {
                        assertNotNull(stream);
                        assertTrue(stream.isReset());
                        streamRef.set(stream);
                        resetLatch.countDown();
                        callback.succeeded();
                    }
                };
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code);
        stream.reset(resetFrame, Callback.NOOP);

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));

        // Wait a while to let the server remove the
        // stream after returning from onReset().
        Thread.sleep(1000);

        Stream serverStream = streamRef.get();
        assertEquals(0, serverStream.getSession().getStreams().size());
    }

    @Test
    public void testStreamResetDoesNotCloseConnection() throws Exception
    {
        final CountDownLatch serverResetLatch = new CountDownLatch(1);
        final CountDownLatch serverDataLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, false);
                CompletableFuture<Stream> completable = stream.headers(responseFrame);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        data.release();
                        completable.thenCompose(s -> s.data(new DataFrame(s.getId(), ByteBuffer.allocate(16), true)))
                            .thenRun(serverDataLatch::countDown);
                    }

                    @Override
                    public void onReset(Stream s, ResetFrame frame, Callback callback)
                    {
                        // Simulate that there is pending data to send.
                        HTTP2Stream stream = (HTTP2Stream)s;
                        List<Frame> frames = List.of(new DataFrame(s.getId(), ByteBuffer.allocate(16), true));
                        stream.getSession().frames(stream, frames, new Callback()
                        {
                            @Override
                            public void failed(Throwable x)
                            {
                                serverResetLatch.countDown();
                            }
                        });
                        callback.succeeded();
                    }
                };
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request1 = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame1 = new HeadersFrame(request1, null, false);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        final CountDownLatch stream1HeadersLatch = new CountDownLatch(1);
        final CountDownLatch stream1DataLatch = new CountDownLatch(1);
        client.newStream(requestFrame1, promise1, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                stream1HeadersLatch.countDown();
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                stream1DataLatch.countDown();
            }
        });
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);
        assertTrue(stream1HeadersLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request2 = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame2 = new HeadersFrame(request2, null, false);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        final CountDownLatch stream2DataLatch = new CountDownLatch(1);
        client.newStream(requestFrame2, promise2, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                stream2DataLatch.countDown();
            }
        });
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);

        ResetFrame resetFrame = new ResetFrame(stream1.getId(), ErrorCode.CANCEL_STREAM_ERROR.code);
        stream1.reset(resetFrame, Callback.NOOP);

        assertTrue(serverResetLatch.await(5, TimeUnit.SECONDS));
        // Stream MUST NOT receive data sent by server after reset.
        assertFalse(stream1DataLatch.await(1, TimeUnit.SECONDS));

        // The other stream should still be working.
        stream2.data(new DataFrame(stream2.getId(), ByteBuffer.allocate(16), true), Callback.NOOP);
        assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        assertTrue(stream2DataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingWriteAfterStreamReceivingReset() throws Exception
    {
        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch resetLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                Charset charset = StandardCharsets.UTF_8;
                byte[] data = "AFTER RESET".getBytes(charset);

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + charset.name());
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.length * 10L);
                Content.Sink.write(response, false, null);
                // Wait for the commit callback to complete.
                commitLatch.countDown();

                try
                {
                    // Wait for the reset to be sent.
                    assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
                    // Wait for the reset to arrive to the server and be processed.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }

                try
                {
                    // Write some content after the stream has
                    // been reset, it should throw an exception.
                    for (int i = 0; i < 100; i++)
                    {
                        Thread.sleep(100);
                        Content.Sink.write(response, false, ByteBuffer.wrap(data));
                    }
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }
                catch (IOException x)
                {
                    dataLatch.countDown();
                }
                return true;
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        client.newStream(frame, new FuturePromise<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                try
                {
                    commitLatch.await(5, TimeUnit.SECONDS);
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code))
                        .thenRun(resetLatch::countDown);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }
        });

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    // TODO: This test writes after a failure and highlights some problem in the implementation
    //  of the handling of errors. For example, the request._error field is set by the failure,
    //  but checked during the succeed of the callback (so cannot turn a failure into a success)
    //  and also highlights that the implementation should be more precise at severing the link
    //  between channel and request, possibly where the request only has one field, the channel.
    @Test
    @Disabled
    public void testAsyncWriteAfterStreamReceivingReset() throws Exception
    {
        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch resetLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                Charset charset = StandardCharsets.UTF_8;
                ByteBuffer data = charset.encode("AFTER RESET");

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + charset.name());
                response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, data.remaining());
                Content.Sink.write(response, false, null);

                commitLatch.countDown();

                try
                {
                    // Wait for the reset to happen.
                    assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
                    // Wait for the reset to arrive to the server and be processed.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }

                // Write some content asynchronously after the stream has been reset.
                new Thread(() ->
                {
                    try
                    {
                        // Wait for the request thread to exit
                        // doGet() so this is really asynchronous.
                        Thread.sleep(1000);

                        response.write(true, data, Callback.from(callback::succeeded, x ->
                        {
                            callback.succeeded();
                            dataLatch.countDown();
                        }));
                    }
                    catch (Throwable x)
                    {
                        x.printStackTrace();
                    }
                }).start();
                return true;
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        client.newStream(frame, new FuturePromise<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                try
                {
                    commitLatch.await(5, TimeUnit.SECONDS);
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code))
                        .thenRun(resetLatch::countDown);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }
        });

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientResetConsumesQueuedData() throws Exception
    {
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                // Wait for the data to be sent.
                assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
                callback.succeeded();
                return true;
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        stream.data(new DataFrame(stream.getId(), data, false), Callback.from(dataLatch::countDown));
        // The server does not read the data, so the flow control window should be zero.
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        assertEquals(0, ((HTTP2Session)client).updateSendWindow(0));

        // Now reset the stream.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

        // Wait for the server to receive the reset and process
        // it, and for the client to process the window updates.
        Thread.sleep(1000);

        assertThat(((HTTP2Session)client).updateSendWindow(0), Matchers.greaterThan(0));
    }

    @Test
    public void testClientResetConsumesQueuedRequestWithData() throws Exception
    {
        // Use a small thread pool.
        QueuedThreadPool serverExecutor = new QueuedThreadPool(5);
        serverExecutor.setName("server");
        serverExecutor.setDetailedDump(true);
        server = new Server(serverExecutor);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        h2.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        h2.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connector = new ServerConnector(server, 1, 1, h2);
        server.addConnector(connector);
        AtomicReference<CountDownLatch> requestOnServer = new AtomicReference<>();
        AtomicBoolean blocker = new AtomicBoolean(true);
        Object lock = new Object();
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                requestOnServer.get().countDown();

                // Block all threads until notified.
                synchronized (lock)
                {
                    while (blocker.get())
                    {
                        lock.wait();
                    }
                }

                Content.copy(request, response, callback);
                return true;
            }
        });
        server.start();

        prepareClient();
        httpClient.start();

        Session client = newClientSession(new Session.Listener() {});

        // Send requests until one is queued on the server but not dispatched.
        AtomicReference<CountDownLatch> latch = new AtomicReference<>();
        List<Stream> streams = new ArrayList<>();
        int count = 0;
        while (true)
        {
            ++count;
            requestOnServer.set(new CountDownLatch(1));

            MetaData.Request request = newRequest("GET", "/" + count, HttpFields.EMPTY);
            HeadersFrame frame = new HeadersFrame(request, null, false);
            FuturePromise<Stream> promise = new FuturePromise<>();
            client.newStream(frame, promise, new Stream.Listener()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    if (response.getStatus() == HttpStatus.OK_200)
                        latch.get().countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(Stream stream)
                {
                    Stream.Data data = stream.readData();
                    data.release();
                    if (data.frame().isEndStream())
                        latch.get().countDown();
                    else
                        stream.demand();
                }
            });
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            streams.add(stream);
            ByteBuffer data = ByteBuffer.allocate(10);
            stream.data(new DataFrame(stream.getId(), data, false), Callback.NOOP);

            // Exit the loop when a request is queued.
            if (!requestOnServer.get().await(1, TimeUnit.SECONDS))
                break;
        }

        // Send one more request to consume the whole session flow control window, then reset it.
        MetaData.Request request = newRequest("GET", "/x", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        // This request will get no event from the server since it's reset by the client.
        client.newStream(frame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(((HTTP2Session)client).updateSendWindow(0));
        stream.data(new DataFrame(stream.getId(), data, false), new Callback()
        {
            @Override
            public void succeeded()
            {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), NOOP);
            }
        });

        // Wait for WINDOW_UPDATEs to be processed by the client.
        await().atMost(1000, TimeUnit.SECONDS).until(() -> ((HTTP2Session)client).updateSendWindow(0), Matchers.greaterThan(0));

        latch.set(new CountDownLatch(2 * streams.size()));
        // Notify all blocked threads to wakeup.
        blocker.set(false);
        synchronized (lock)
        {
            lock.notifyAll();
        }
        // Complete all streams.
        streams.forEach(s -> s.data(new DataFrame(s.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP));

        assertTrue(latch.get().await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerExceptionConsumesQueuedData() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(Response.class))
        {
            start(new Handler.Abstract()
            {
                @Override
                public boolean process(Request request, Response response, Callback callback) throws Exception
                {
                    // Wait to let the data sent by the client to be queued.
                    Thread.sleep(1000);
                    throw new IllegalStateException("explicitly_thrown_by_test");
                }
            });

            Session client = newClientSession(new Session.Listener() {});

            MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
            HeadersFrame frame = new HeadersFrame(request, null, false);
            FuturePromise<Stream> promise = new FuturePromise<>();
            client.newStream(frame, promise, null);
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
            CountDownLatch dataLatch = new CountDownLatch(1);
            stream.data(new DataFrame(stream.getId(), data, false), new Callback()
            {
                @Override
                public void succeeded()
                {
                    dataLatch.countDown();
                }
            });
            // The server does not read the data, so the flow control window should be zero.
            assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
            assertEquals(0, ((HTTP2Session)client).updateSendWindow(0));

            // Wait for the server process the exception, and
            // for the client to process the window updates.
            Thread.sleep(2000);

            assertThat(((HTTP2Session)client).updateSendWindow(0), Matchers.greaterThan(0));
        }
    }

    @Test
    public void testResetAfterAsyncRequestBlockingWriteStalledByFlowControl() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                new Thread(() ->
                {
                    try
                    {
                        // Make sure we are in async wait before writing.
                        Thread.sleep(1000);
                        Content.Sink.write(response, true, ByteBuffer.wrap(new byte[10 * windowSize]));
                    }
                    catch (IOException x)
                    {
                        writeLatch.countDown();
                    }
                    catch (Throwable x)
                    {
                        x.printStackTrace();
                    }
                }).start();
                return true;
            }
        });

        Deque<Stream.Data> dataQueue = new ArrayDeque<>();
        AtomicLong received = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                dataQueue.offer(data);
                // Do not consume the data yet.
                if (received.addAndGet(data.frame().getData().remaining()) == windowSize)
                    latch.countDown();
                else
                    stream.demand();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Reset and consume.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        dataQueue.forEach(Stream.Data::release);

        assertTrue(writeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResetAfterBlockingWrite() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                try
                {
                    Content.Sink.write(response, true, ByteBuffer.wrap(new byte[10 * windowSize]));
                }
                catch (IOException x)
                {
                    writeLatch.countDown();
                }
                return true;
            }
        });

        AtomicLong received = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                // Do not release to stall the flow control window.
                if (received.addAndGet(data.frame().getData().remaining()) == windowSize)
                    latch.countDown();
                else
                    stream.demand();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Reset.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        assertTrue(writeLatch.await(5, TimeUnit.SECONDS));

        // Give time to the server to process the reset and drain the flusher queue.
        Thread.sleep(500);

        AbstractHTTP2ServerConnectionFactory http2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        Set<Session> sessions = http2.getBean(AbstractHTTP2ServerConnectionFactory.HTTP2SessionContainer.class).getSessions();
        assertEquals(1, sessions.size());
        HTTP2Session session = (HTTP2Session)sessions.iterator().next();
        HTTP2Flusher flusher = session.getBean(HTTP2Flusher.class);
        assertEquals(0, flusher.getFrameQueueSize());
    }

    @Test
    public void testResetAfterAsyncRequestAsyncWriteStalledByFlowControl() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                response.write(true, ByteBuffer.wrap(new byte[10 * windowSize]), Callback.from(callback::succeeded, x ->
                {
                    writeLatch.countDown();
                    callback.succeeded();
                }));
                return true;
            }
        });

        Deque<Stream.Data> dataQueue = new ArrayDeque<>();
        AtomicLong received = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                dataQueue.offer(data);
                // Do not consume the data yet.
                if (received.addAndGet(data.frame().getData().remaining()) == windowSize)
                    latch.countDown();
                else
                    stream.demand();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Reset and consume.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        dataQueue.forEach(Stream.Data::release);

        assertTrue(writeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResetBeforeBlockingRead() throws Exception
    {
        CountDownLatch requestLatch = new CountDownLatch(1);
        CountDownLatch readLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                try
                {
                    requestLatch.countDown();
                    readLatch.await();

                    // Attempt to read after reset must throw.
                    Content.Source.asByteBuffer(request);
                }
                catch (IOException expected)
                {
                    failureLatch.countDown();
                }
                return true;
            }
        });

        Session client = newClientSession(new Session.Listener() {});

        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer content = ByteBuffer.wrap(new byte[1024]);
        stream.data(new DataFrame(stream.getId(), content, true), Callback.NOOP);

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS));

        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        // Wait for the reset to arrive to the server and be processed.
        Thread.sleep(1000);

        // Try to read on server.
        readLatch.countDown();
        // Read on server should fail.
        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResetAfterTCPCongestedWrite() throws Exception
    {
        AtomicReference<WriteFlusher> flusherRef = new AtomicReference<>();
        CountDownLatch flusherLatch = new CountDownLatch(1);
        CountDownLatch writeLatch1 = new CountDownLatch(1);
        CountDownLatch writeLatch2 = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                flusherRef.set(((AbstractEndPoint)request.getConnectionMetaData().getConnection().getEndPoint()).getWriteFlusher());
                flusherLatch.countDown();

                try
                {
                    // Large write, it blocks due to TCP congestion.
                    byte[] data = new byte[128 * 1024 * 1024];
                    Content.Sink.write(response, false, ByteBuffer.wrap(data));
                }
                catch (IOException x)
                {
                    writeLatch1.countDown();
                    try
                    {
                        // Try to write again, must fail immediately.
                        Content.Sink.write(response, true, ByteBuffer.wrap(new byte[]{1}));
                    }
                    catch (IOException e)
                    {
                        writeLatch2.countDown();
                    }
                }
                return true;
            }
        });

        ByteBufferPool byteBufferPool = http2Client.getByteBufferPool();
        try (SocketChannel socket = SocketChannel.open())
        {
            String host = "localhost";
            int port = connector.getLocalPort();
            socket.connect(new InetSocketAddress(host, port));

            Generator generator = new Generator(byteBufferPool);
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.control(lease, new PrefaceFrame());
            Map<Integer, Integer> clientSettings = new HashMap<>();
            // Max stream HTTP/2 flow control window.
            clientSettings.put(SettingsFrame.INITIAL_WINDOW_SIZE, Integer.MAX_VALUE);
            generator.control(lease, new SettingsFrame(clientSettings, false));
            // Max session HTTP/2 flow control window.
            generator.control(lease, new WindowUpdateFrame(0, Integer.MAX_VALUE - FlowControlStrategy.DEFAULT_WINDOW_SIZE));

            HttpURI uri = HttpURI.from("http", host, port, "/");
            MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_2, HttpFields.EMPTY);
            int streamId = 3;
            HeadersFrame headersFrame = new HeadersFrame(streamId, request, null, true);
            generator.control(lease, headersFrame);

            List<ByteBuffer> buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[0]));

            // Wait until the server is TCP congested.
            assertTrue(flusherLatch.await(5, TimeUnit.SECONDS));
            WriteFlusher flusher = flusherRef.get();
            waitUntilTCPCongested(flusher);

            lease.recycle();
            generator.control(lease, new ResetFrame(streamId, ErrorCode.CANCEL_STREAM_ERROR.code));
            buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[0]));

            assertTrue(writeLatch1.await(5, TimeUnit.SECONDS));
            assertTrue(writeLatch2.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testResetSecondRequestAfterTCPCongestedWriteBeforeWrite() throws Exception
    {
        Exchanger<WriteFlusher> exchanger = new Exchanger<>();
        CountDownLatch requestLatch1 = new CountDownLatch(1);
        CountDownLatch requestLatch2 = new CountDownLatch(1);
        CountDownLatch writeLatch1 = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback) throws Exception
            {
                String target = Request.getPathInContext(request);
                if (target.equals("/1"))
                    service1(request, response, callback);
                else if (target.equals("/2"))
                    service2(response, callback);
                else
                    throw new IllegalArgumentException();
                return true;
            }

            private void service1(Request request, Response response, Callback callback) throws Exception
            {
                exchanger.exchange(((AbstractEndPoint)request.getConnectionMetaData().getConnection().getEndPoint()).getWriteFlusher());
                // Large write, it blocks due to TCP congestion.
                response.write(true, ByteBuffer.wrap(new byte[128 * 1024 * 1024]), callback);
            }

            private void service2(Response response, Callback callback) throws Exception
            {
                try
                {
                    requestLatch1.countDown();
                    requestLatch2.await();
                    int length = 512 * 1024;
                    AbstractHTTP2ServerConnectionFactory h2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
                    if (h2 != null)
                        length = h2.getHttpConfiguration().getOutputAggregationSize();
                    // Medium write, so we don't aggregate it, must not block.
                    Content.Sink.write(response, true, ByteBuffer.wrap(new byte[length * 2]));
                }
                catch (IOException x)
                {
                    writeLatch1.countDown();
                    callback.succeeded();
                }
            }
        });

        ByteBufferPool byteBufferPool = http2Client.getByteBufferPool();
        try (SocketChannel socket = SocketChannel.open())
        {
            String host = "localhost";
            int port = connector.getLocalPort();
            socket.connect(new InetSocketAddress(host, port));

            Generator generator = new Generator(byteBufferPool);
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.control(lease, new PrefaceFrame());
            Map<Integer, Integer> clientSettings = new HashMap<>();
            // Max stream HTTP/2 flow control window.
            clientSettings.put(SettingsFrame.INITIAL_WINDOW_SIZE, Integer.MAX_VALUE);
            generator.control(lease, new SettingsFrame(clientSettings, false));
            // Max session HTTP/2 flow control window.
            generator.control(lease, new WindowUpdateFrame(0, Integer.MAX_VALUE - FlowControlStrategy.DEFAULT_WINDOW_SIZE));

            HttpURI uri = HttpURI.from("http", host, port, "/1");
            MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_2, HttpFields.EMPTY);
            HeadersFrame headersFrame = new HeadersFrame(3, request, null, true);
            generator.control(lease, headersFrame);

            List<ByteBuffer> buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[0]));

            waitUntilTCPCongested(exchanger.exchange(null));

            // Send a second request.
            uri = HttpURI.from("http", host, port, "/2");
            request = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_2, HttpFields.EMPTY);
            int streamId = 5;
            headersFrame = new HeadersFrame(streamId, request, null, true);
            generator.control(lease, headersFrame);
            buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[0]));
            assertTrue(requestLatch1.await(5, TimeUnit.SECONDS));

            // Now reset the second request, which has not started writing yet.
            lease.recycle();
            generator.control(lease, new ResetFrame(streamId, ErrorCode.CANCEL_STREAM_ERROR.code));
            buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[0]));
            // Wait to be sure that the server processed the reset.
            Thread.sleep(1000);
            // Let the request write, it should not block.
            requestLatch2.countDown();
            assertTrue(writeLatch1.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testResetBeforeReceivingWindowUpdate() throws Exception
    {
        int window = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        float ratio = 0.5F;
        AtomicReference<Stream> streamRef = new AtomicReference<>();
        Consumer<AbstractHTTP2ServerConnectionFactory> http2Factory = http2 ->
        {
            http2.setInitialSessionRecvWindow(window);
            http2.setInitialStreamRecvWindow(window);
            http2.setFlowControlStrategyFactory(() -> new BufferingFlowControlStrategy(ratio)
            {
                @Override
                protected void sendWindowUpdate(Session session, Stream stream, List<WindowUpdateFrame> frames)
                {
                    // Before sending the window update, reset from the client side.
                    if (stream != null)
                        streamRef.get().reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                    super.sendWindowUpdate(session, stream, frames);
                }
            });
        };
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, false);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        }, http2Factory);

        CountDownLatch failureLatch = new CountDownLatch(1);
        Session client = newClientSession(new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                failureLatch.countDown();
                callback.succeeded();
            }
        });
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        streamRef.set(stream);
        // Send enough bytes to trigger the server to send a window update.
        ByteBuffer content = ByteBuffer.allocate((int)(window * ratio) + 1024);
        stream.data(new DataFrame(stream.getId(), content, false), Callback.NOOP);

        assertFalse(failureLatch.await(1, TimeUnit.SECONDS));
    }

    private void waitUntilTCPCongested(WriteFlusher flusher) throws TimeoutException, InterruptedException
    {
        long start = NanoTime.now();
        while (!flusher.isPending())
        {
            if (NanoTime.secondsSince(start) > 15)
                throw new TimeoutException();
            Thread.sleep(100);
        }
        // Wait for the selector to update the SelectionKey to OP_WRITE.
        Thread.sleep(1000);
    }
}
