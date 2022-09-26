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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.internal.ErrorCode;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.http2.internal.HTTP2Stream;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public abstract class FlowControlStrategyTest
{
    protected ServerConnector connector;
    protected HTTP2Client client;
    protected Server server;

    protected abstract FlowControlStrategy newFlowControlStrategy();

    protected void start(ServerSessionListener listener) throws Exception
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        RawHTTP2ServerConnectionFactory connectionFactory = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener);
        connectionFactory.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connectionFactory.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connectionFactory.setFlowControlStrategyFactory(FlowControlStrategyTest.this::newFlowControlStrategy);
        connector = new ServerConnector(server, connectionFactory);
        server.addConnector(connector);
        server.start();

        client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        client.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        client.setFlowControlStrategyFactory(FlowControlStrategyTest.this::newFlowControlStrategy);
        client.start();
    }

    protected Session newClient(Session.Listener listener) throws Exception
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(address, listener, promise);
        return promise.get(5, TimeUnit.SECONDS);
    }

    protected MetaData.Request newRequest(String method, HttpFields fields)
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        return new MetaData.Request(method, HttpScheme.HTTP.asString(), new HostPortHttpField(authority), "/", HttpVersion.HTTP_2, fields, -1);
    }

    @AfterEach
    public void dispose() throws Exception
    {
        // Allow WINDOW_UPDATE frames to be sent/received to avoid exception stack traces.
        Thread.sleep(1000);
        client.stop();
        server.stop();
    }

    @Test
    public void testWindowSizeUpdates() throws Exception
    {
        CountDownLatch prefaceLatch = new CountDownLatch(1);
        CountDownLatch stream1Latch = new CountDownLatch(1);
        CountDownLatch stream2Latch = new CountDownLatch(1);
        CountDownLatch settingsLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                HTTP2Session serverSession = (HTTP2Session)session;
                assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, serverSession.getSendWindow());
                assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, serverSession.getRecvWindow());
                prefaceLatch.countDown();
                return null;
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                for (Stream stream : session.getStreams())
                {
                    HTTP2Stream serverStream = (HTTP2Stream)stream;
                    assertEquals(0, serverStream.getSendWindow());
                    assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, serverStream.getRecvWindow());
                }
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HTTP2Stream serverStream = (HTTP2Stream)stream;
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if ("GET".equalsIgnoreCase(request.getMethod()))
                {
                    assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, serverStream.getSendWindow());
                    assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, serverStream.getRecvWindow());
                    stream1Latch.countDown();
                }
                else
                {
                    assertEquals(0, serverStream.getSendWindow());
                    assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, serverStream.getRecvWindow());
                    stream2Latch.countDown();
                }
                return null;
            }
        });

        HTTP2Session clientSession = (HTTP2Session)newClient(new Session.Listener() {});

        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientSession.getSendWindow());
        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientSession.getRecvWindow());
        assertTrue(prefaceLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request1 = newRequest("GET", HttpFields.EMPTY);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        clientSession.newStream(new HeadersFrame(request1, null, true), promise1, null);
        HTTP2Stream clientStream1 = (HTTP2Stream)promise1.get(5, TimeUnit.SECONDS);

        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientStream1.getSendWindow());
        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientStream1.getRecvWindow());
        assertTrue(stream1Latch.await(5, TimeUnit.SECONDS));

        // Send a SETTINGS frame that changes the window size.
        // This tells the server that its stream send window must be updated,
        // so on the client it's the receive window that must be updated.
        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, 0);
        SettingsFrame frame = new SettingsFrame(settings, false);
        FutureCallback callback = new FutureCallback();
        clientSession.settings(frame, callback);
        callback.get(5, TimeUnit.SECONDS);

        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientStream1.getSendWindow());
        assertEquals(0, clientStream1.getRecvWindow());
        settingsLatch.await(5, TimeUnit.SECONDS);

        // Now create a new stream, it must pick up the new value.
        MetaData.Request request2 = newRequest("POST", HttpFields.EMPTY);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        clientSession.newStream(new HeadersFrame(request2, null, true), promise2, null);
        HTTP2Stream clientStream2 = (HTTP2Stream)promise2.get(5, TimeUnit.SECONDS);

        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientStream2.getSendWindow());
        assertEquals(0, clientStream2.getRecvWindow());
        assertTrue(stream2Latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testFlowControlWithConcurrentSettings() throws Exception
    {
        // Initial window is 64 KiB. We allow the client to send 1024 B
        // then we change the window to 512 B. At this point, the client
        // must stop sending data (although the initial window allows it).

        int size = 512;
        // We get 3 data frames: the first of 1024 and 2 of 512 each
        // after the flow control window has been reduced.
        CountDownLatch dataLatch = new CountDownLatch(3);
        AtomicReference<Stream.Data> dataRef = new AtomicReference<>();
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                stream.demand();
                return new Stream.Listener()
                {
                    private final AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        dataLatch.countDown();
                        int dataFrameCount = dataFrames.incrementAndGet();
                        if (dataFrameCount == 1)
                        {
                            dataRef.set(data);
                            Map<Integer, Integer> settings = new HashMap<>();
                            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, size);
                            stream.getSession().settings(new SettingsFrame(settings, false), Callback.NOOP);
                            stream.demand();
                            // Do not release the data here.
                        }
                        else if (dataFrameCount > 1)
                        {
                            // Release the data.
                            data.release();
                            if (!data.frame().isEndStream())
                                stream.demand();
                        }
                    }
                };
            }
        });

        // Two SETTINGS frames, the initial one and the one we send from the server.
        CountDownLatch settingsLatch = new CountDownLatch(2);
        Session session = newClient(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        MetaData.Request request = newRequest("POST", HttpFields.EMPTY);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(new HeadersFrame(request, null, false), promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Send first chunk that exceeds the window.
        CompletableFuture<Stream> completable = stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(size * 2), false));
        settingsLatch.await(5, TimeUnit.SECONDS);

        completable.thenAccept(s ->
        {
            // Send the second chunk of data, must not arrive since we're flow control stalled on the client.
            s.data(new DataFrame(s.getId(), ByteBuffer.allocate(size * 2), true));
        });

        assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        // Release the data arrived to server, this will resume flow control on the client.
        dataRef.get().release();

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerFlowControlOneBigWrite() throws Exception
    {
        int windowSize = 1536;
        int length = 5 * windowSize;
        CountDownLatch settingsLatch = new CountDownLatch(2);
        start(new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                CompletableFuture<Void> completable = new CompletableFuture<>();
                stream.headers(responseFrame, Callback.from(completable));
                completable.thenRun(() ->
                {
                    DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(length), true);
                    stream.data(dataFrame, Callback.NOOP);
                });
                return null;
            }
        });

        Session session = newClient(new Session.Listener() {});

        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, windowSize);
        session.settings(new SettingsFrame(settings, false))
            .thenRun(settingsLatch::countDown);

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch dataLatch = new CountDownLatch(1);
        Exchanger<Stream.Data> exchanger = new Exchanger<>();
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener()
        {
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                try
                {
                    int dataFrames = this.dataFrames.incrementAndGet();
                    if (dataFrames == 1 || dataFrames == 2)
                    {
                        // Do not release the Data.
                        // We should then be flow-control stalled.
                        exchanger.exchange(data);
                        stream.demand();
                    }
                    else if (dataFrames == 3 || dataFrames == 4 || dataFrames == 5)
                    {
                        // Consume totally.
                        data.release();
                        if (data.frame().isEndStream())
                            dataLatch.countDown();
                        else
                            stream.demand();
                    }
                    else
                    {
                        fail("Unrecognized dataFrames: " + dataFrames);
                    }
                }
                catch (InterruptedException x)
                {
                    data.release();
                }
            }
        });

        Stream.Data data = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Release the first chunk.
        data.release();

        data = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Release the second chunk.
        data.release();

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientFlowControlOneBigWrite() throws Exception
    {
        int windowSize = 1536;
        Exchanger<Stream.Data> exchanger = new Exchanger<>();
        CountDownLatch settingsLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, windowSize);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                stream.demand();
                return new Stream.Listener()
                {
                    private final AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        try
                        {
                            int dataFrames = this.dataFrames.incrementAndGet();
                            if (dataFrames == 1 || dataFrames == 2)
                            {
                                // Do not consume the data frame.
                                // We should then be flow-control stalled.
                                exchanger.exchange(data);
                                stream.demand();
                            }
                            else if (dataFrames == 3 || dataFrames == 4 || dataFrames == 5)
                            {
                                // Consume totally.
                                data.release();
                                if (data.frame().isEndStream())
                                    dataLatch.countDown();
                                else
                                    stream.demand();
                            }
                            else
                            {
                                fail("Unrecognized dataFrames: " + dataFrames);
                            }
                        }
                        catch (InterruptedException x)
                        {
                            data.release();
                        }
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, null);
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        int length = 5 * windowSize;
        DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(length), true);
        stream.data(dataFrame, Callback.NOOP);

        Stream.Data data = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the first chunk.
        data.release();

        data = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the second chunk.
        data.release();

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    private void checkThatWeAreFlowControlStalled(Exchanger<Stream.Data> exchanger)
    {
        assertThrows(TimeoutException.class,
            () -> exchanger.exchange(null, 1, TimeUnit.SECONDS));
    }

    @Test
    public void testSessionStalledStallsNewStreams() throws Exception
    {
        int windowSize = 1024;
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Request request = (MetaData.Request)requestFrame.getMetaData();
                if (HttpMethod.POST.is(request.getMethod()))
                {
                    MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                    stream.headers(new HeadersFrame(stream.getId(), metaData, null, false))
                        .thenCompose(s ->
                        {
                            // Send data to consume most of the session window.
                            ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE - windowSize);
                            DataFrame dataFrame = new DataFrame(s.getId(), data, true);
                            return s.data(dataFrame);
                        });
                    return null;
                }
                else
                {
                    // For every stream, send down half the window size of data.
                    MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                    stream.headers(new HeadersFrame(stream.getId(), metaData, null, false))
                        .thenCompose(s ->
                        {
                            DataFrame dataFrame = new DataFrame(s.getId(), ByteBuffer.allocate(windowSize / 2), true);
                            return s.data(dataFrame);
                        });
                    return null;
                }
            }
        });

        Session session = newClient(new Session.Listener() {});

        // First request is just to consume most of the session window.
        List<Stream.Data> dataList1 = new ArrayList<>();
        CountDownLatch prepareLatch = new CountDownLatch(1);
        MetaData.Request request1 = newRequest("POST", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request1, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                // Do not consume the data to reduce the session window.
                dataList1.add(data);
                if (data.frame().isEndStream())
                    prepareLatch.countDown();
                else
                    stream.demand();
            }
        });
        assertTrue(prepareLatch.await(5, TimeUnit.SECONDS));

        // Second request will consume half of the remaining the session window.
        MetaData.Request request2 = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request2, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                if (!data.frame().isEndStream())
                    stream.demand();
                // Do not release it to stall flow control.
            }
        });

        // Third request will consume the whole session window, which is now stalled.
        // A fourth request will not be able to receive data.
        MetaData.Request request3 = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request3, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                if (!data.frame().isEndStream())
                    stream.demand();
                // Do not release it to stall flow control.
            }
        });

        // Fourth request is now stalled.
        CountDownLatch latch = new CountDownLatch(1);
        MetaData.Request request4 = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request4, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        });

        // Verify that the data does not arrive because the server session is stalled.
        assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Consume the data of the first response.
        // This will open up the session window, allowing the fourth stream to send data.
        dataList1.forEach(Stream.Data::release);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSendsBigContent() throws Exception
    {
        byte[] data = new byte[1024 * 1024];
        new Random().nextBytes(data);

        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame)
                    .thenAccept(s -> s.data(new DataFrame(s.getId(), ByteBuffer.wrap(data), true)));
                return null;
            }
        });

        Session session = newClient(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        byte[] bytes = new byte[data.length];
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener()
        {
            private int received;

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                DataFrame frame = data.frame();
                int remaining = frame.remaining();
                frame.getData().get(bytes, received, remaining);
                this.received += remaining;
                data.release();
                if (frame.isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertArrayEquals(data, bytes);
    }

    @Test
    public void testClientSendingInitialSmallWindow() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                CompletableFuture<Stream> completable = stream.headers(responseFrame);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        completable.thenAccept(s -> s.data(data.frame())
                            .whenComplete((r, x) ->
                            {
                                data.release();
                                if (!data.frame().isEndStream())
                                    stream.demand();
                            }));
                    }
                };
            }
        });

        int initialWindow = 16;
        Session session = newClient(new Session.Listener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, initialWindow);
                return settings;
            }
        });

        byte[] requestData = new byte[initialWindow * 4];
        new Random().nextBytes(requestData);

        byte[] responseData = new byte[requestData.length];
        ByteBuffer responseContent = ByteBuffer.wrap(responseData);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                responseContent.put(data.frame().getData());
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        })
        .thenAccept(s ->
        {
            ByteBuffer requestContent = ByteBuffer.wrap(requestData);
            s.data(new DataFrame(s.getId(), requestContent, true));
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        responseContent.flip();
        assertArrayEquals(requestData, responseData);
    }

    @Test
    public void testClientExceedingSessionWindow() throws Exception
    {
        // On server, we don't consume the data.
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // Read but do not release the Data.
                        Stream.Data data = stream.readData();
                        if (!data.frame().isEndStream())
                            stream.demand();
                    }
                };
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseLatch.countDown();
                callback.succeeded();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.getError() == ErrorCode.FLOW_CONTROL_ERROR.code)
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });

        // Consume the whole session and stream window.
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        CompletableFuture<Stream> completable = new CompletableFuture<>();
        session.newStream(requestFrame, Promise.from(completable), null);
        Stream stream = completable.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, false), new Callback()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void succeeded()
            {
                dataLatch.countDown();
            }
        });
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        // The following "sneaky" write may clash with the write
        // of the reply SETTINGS frame sent by the client in
        // response to the server SETTINGS frame.
        // It is not enough to use a latch on the server to
        // wait for the reply frame, since the client may have
        // sent the bytes, but not yet be ready to write again.
        Thread.sleep(1000);

        // Now the client is supposed to not send more frames.
        // If it does, the connection must be closed.
        HTTP2Session http2Session = (HTTP2Session)session;
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(connector.getByteBufferPool());
        ByteBuffer extraData = ByteBuffer.allocate(1024);
        http2Session.getGenerator().data(lease, new DataFrame(stream.getId(), extraData, true), extraData.remaining());
        List<ByteBuffer> buffers = lease.getByteBuffers();
        http2Session.getEndPoint().write(Callback.NOOP, buffers.toArray(new ByteBuffer[0]));

        // Expect the connection to be closed.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientExceedingStreamWindow() throws Exception
    {
        // On server, we don't consume the data.
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                // Enlarge the session window.
                ((HTTP2Session)session).updateRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
                return null;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // Read but do not release the Data.
                        Stream.Data data = stream.readData();
                        if (!data.frame().isEndStream())
                            stream.demand();
                    }
                };
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseLatch.countDown();
                callback.succeeded();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.getError() == ErrorCode.FLOW_CONTROL_ERROR.code)
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });

        // Consume the whole stream window.
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, null);
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, false), new Callback()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void succeeded()
            {
                dataLatch.countDown();
            }
        });
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        // Wait for a while before doing the "sneaky" write
        // below, see comments in the previous test case.
        Thread.sleep(1000);

        // Now the client is supposed to not send more frames.
        // If it does, the connection must be closed.
        HTTP2Session http2Session = (HTTP2Session)session;
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(connector.getByteBufferPool());
        ByteBuffer extraData = ByteBuffer.allocate(1024);
        http2Session.getGenerator().data(lease, new DataFrame(stream.getId(), extraData, true), extraData.remaining());
        List<ByteBuffer> buffers = lease.getByteBuffers();
        http2Session.getEndPoint().write(Callback.NOOP, buffers.toArray(new ByteBuffer[0]));

        // Expect the connection to be closed.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testFlowControlWhenServerResetsStream() throws Exception
    {
        // On server, don't consume the data and immediately reset.
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if (HttpMethod.GET.is(request.getMethod()))
                    return null;
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        // Release the data to enlarge the session window.
                        // More data frames will be discarded because the
                        // stream is reset, and automatically consumed to
                        // keep the session window large for other streams.
                        data.release();
                        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener() {});
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(frame, streamPromise, new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                resetLatch.countDown();
                callback.succeeded();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        // Perform a big upload that will stall the flow control windows.
        ByteBuffer data = ByteBuffer.allocate(5 * FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, true), new Callback()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void failed(Throwable x)
            {
                dataLatch.countDown();
            }
        });

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNoWindowUpdateForRemotelyClosedStream() throws Exception
    {
        List<Stream.Data> dataList = new ArrayList<>();
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        dataList.add(data);
                        if (data.frame().isEndStream())
                        {
                            // Release the Data when the stream is already remotely closed.
                            dataList.forEach(Stream.Data::release);
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        }
                        else
                        {
                            stream.demand();
                        }
                    }
                };
            }
        });

        List<WindowUpdateFrame> sessionWindowUpdates = new ArrayList<>();
        List<WindowUpdateFrame> streamWindowUpdates = new ArrayList<>();
        client.setFlowControlStrategyFactory(() -> new BufferingFlowControlStrategy(0.5F)
        {
            @Override
            public void onWindowUpdate(Session session, Stream stream, WindowUpdateFrame frame)
            {
                if (frame.getStreamId() == 0)
                    sessionWindowUpdates.add(frame);
                else
                    streamWindowUpdates.add(frame);
                super.onWindowUpdate(session, stream, frame);
            }
        });

        Session session = newClient(new Session.Listener() {});
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, streamPromise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE - 1);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertTrue(sessionWindowUpdates.size() > 0);
        assertEquals(0, streamWindowUpdates.size());
    }
}
