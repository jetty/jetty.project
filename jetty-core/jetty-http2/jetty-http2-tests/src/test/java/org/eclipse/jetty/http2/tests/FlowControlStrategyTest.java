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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.AbstractFlowControlStrategy;
import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.SimpleFlowControlStrategy;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlowControlStrategyTest
{
    private ServerConnector connector;
    private HTTP2Client client;
    private Server server;

    protected void start(FlowControlStrategyType type, ServerSessionListener listener) throws Exception
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        RawHTTP2ServerConnectionFactory connectionFactory = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener);
        connectionFactory.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connectionFactory.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connectionFactory.setFlowControlStrategyFactory(() -> newFlowControlStrategy(type));
        connector = new ServerConnector(server, connectionFactory);
        server.addConnector(connector);
        server.start();

        client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        client.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        client.setFlowControlStrategyFactory(() -> newFlowControlStrategy(type));
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

    protected FlowControlStrategy newFlowControlStrategy(FlowControlStrategyType type)
    {
        return switch (type)
        {
            case SIMPLE -> new SimpleFlowControlStrategy();
            case BUFFERING -> new BufferingFlowControlStrategy(0.5F);
        };
    }

    @AfterEach
    public void dispose() throws Exception
    {
        // Allow WINDOW_UPDATE frames to be sent/received to avoid exception stack traces.
        Thread.sleep(1000);
        client.stop();
        server.stop();
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testWindowSizeUpdates(FlowControlStrategyType type) throws Exception
    {
        CountDownLatch prefaceLatch = new CountDownLatch(1);
        CountDownLatch stream1Latch = new CountDownLatch(1);
        CountDownLatch stream2Latch = new CountDownLatch(1);
        CountDownLatch settingsLatch = new CountDownLatch(1);
        start(type, new ServerSessionListener()
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

        await().atMost(5, TimeUnit.SECONDS).until(() -> clientStream1.getRecvWindow() == 0);
        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientStream1.getSendWindow());

        // Now create a new stream, it must pick up the new value.
        MetaData.Request request2 = newRequest("POST", HttpFields.EMPTY);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        clientSession.newStream(new HeadersFrame(request2, null, true), promise2, null);
        HTTP2Stream clientStream2 = (HTTP2Stream)promise2.get(5, TimeUnit.SECONDS);

        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE, clientStream2.getSendWindow());
        assertEquals(0, clientStream2.getRecvWindow());
        assertTrue(stream2Latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testFlowControlWithConcurrentSettings(FlowControlStrategyType type) throws Exception
    {
        // Initial window is 64 KiB. We allow the client to send 1024 B
        // then we change the window to 512 B. At this point, the client
        // must stop sending data (although the initial window allows it).

        int size = 512;
        AtomicInteger dataAvailable = new AtomicInteger();
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        start(type, new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                serverStreamRef.set(stream);
                MetaData.Response response = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        if (dataAvailable.incrementAndGet() == 1)
                        {
                            // Do not read so the flow control window is not enlarged.
                            // Send the update on the flow control window.
                            Map<Integer, Integer> settings = new HashMap<>();
                            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, size);
                            stream.getSession().settings(new SettingsFrame(settings, false), Callback.NOOP);
                            // Since we did not read, don't demand, otherwise we will be called again.
                        }
                    }
                };
            }
        });

        // Two SETTINGS frames, the initial one and the one for the flow control window update.
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
        Stream stream = session.newStream(new HeadersFrame(request, null, false), null)
            .get(5, TimeUnit.SECONDS);

        // Send first chunk that will exceed the flow control window when the new SETTINGS is received.
        CompletableFuture<Stream> completable = stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(size * 2), false));
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        completable.thenAccept(s ->
        {
            // Send the second chunk of data, must not leave the client since it is flow control stalled.
            s.data(new DataFrame(s.getId(), ByteBuffer.allocate(size * 2), true));
        });

        // Verify that the server only received one data available notification.
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> dataAvailable.get() == 1);

        // Now read from the server, so the flow control window
        // is enlarged and the client can resume sending.
        consumeAll(serverStreamRef.get());
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testServerFlowControlOneBigWrite(FlowControlStrategyType type) throws Exception
    {
        int windowSize = 1536;
        int length = 5 * windowSize;
        CountDownLatch settingsLatch = new CountDownLatch(2);
        start(type, new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
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
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            AbstractFlowControlStrategy flow = (AbstractFlowControlStrategy)((HTTP2Session)session).getFlowControlStrategy();
            return flow.getInitialStreamRecvWindow() == windowSize;
        });

        AtomicReference<Stream> streamRef = new AtomicReference<>();
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                // Do not read to stall the server.
                streamRef.set(stream);
            }
        });
        await().atMost(5, TimeUnit.SECONDS).until(() -> streamRef.get() != null);

        // Did not read yet, verify that we are flow control stalled.
        Stream stream = streamRef.getAndSet(null);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> streamRef.get() == null);

        // Read the first chunk.
        Stream.Data data = stream.readData();
        assertNotNull(data);
        data.release();

        // Did not demand, so onDataAvailable() should not be called.
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> streamRef.get() == null);

        // Demand, onDataAvailable() should be called.
        stream.demand();
        await().atMost(5, TimeUnit.SECONDS).until(() -> streamRef.get() != null);

        // Did not read yet, verify that we are flow control stalled.
        stream = streamRef.getAndSet(null);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> streamRef.get() == null);

        // Read the second chunk.
        data = stream.readData();
        assertNotNull(data);
        data.release();

        consumeAll(stream);
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testClientFlowControlOneBigWrite(FlowControlStrategyType type) throws Exception
    {
        int windowSize = 1536;
        AtomicReference<HTTP2Session> serverSessionRef = new AtomicReference<>();
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        start(type, new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                serverSessionRef.set((HTTP2Session)session);
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, windowSize);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // Do not read to stall the server.
                        serverStreamRef.set(stream);
                    }
                };
            }
        });

        Session clientSession = newClient(new Session.Listener() {});

        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            AbstractFlowControlStrategy flow = (AbstractFlowControlStrategy)serverSessionRef.get().getFlowControlStrategy();
            return flow.getInitialStreamRecvWindow() == windowSize;
        });

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        clientSession.newStream(requestFrame, null)
            .thenCompose(s ->
            {
                int length = 5 * windowSize;
                DataFrame dataFrame = new DataFrame(s.getId(), ByteBuffer.allocate(length), true);
                return s.data(dataFrame);
            });

        // Verify that the data arrived to the server.
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() != null);

        // Did not read yet, verify that we are flow control stalled.
        Stream serverStream = serverStreamRef.getAndSet(null);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() == null);

        // Read the first chunk.
        Stream.Data data = serverStream.readData();
        assertNotNull(data);
        data.release();

        // Did not demand, so onDataAvailable() should not be called.
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() == null);

        // Demand, onDataAvailable() should be called.
        serverStream.demand();
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() != null);

        // Did not read yet, verify that we are flow control stalled.
        serverStream = serverStreamRef.getAndSet(null);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() == null);

        // Read the second chunk.
        data = serverStream.readData();
        assertNotNull(data);
        data.release();

        consumeAll(serverStream);
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testSessionStalledStallsNewStreams(FlowControlStrategyType type) throws Exception
    {
        int windowSize = 1024;
        start(type, new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Request request = (MetaData.Request)requestFrame.getMetaData();
                if (HttpMethod.POST.is(request.getMethod()))
                {
                    MetaData.Response metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
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
                    MetaData.Response metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
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
        AtomicReference<Stream> streamRef1 = new AtomicReference<>();
        MetaData.Request request1 = newRequest("POST", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request1, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                // Do not read to stall flow control.
                streamRef1.set(stream);
            }
        });
        await().atMost(5, TimeUnit.SECONDS).until(() -> streamRef1.get() != null);

        // Second request will consume half of the remaining the session window.
        AtomicReference<Stream> streamRef2 = new AtomicReference<>();
        MetaData.Request request2 = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request2, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                // Do not read to stall flow control.
                streamRef2.set(stream);
            }
        });
        await().atMost(5, TimeUnit.SECONDS).until(() -> streamRef2.get() != null);

        // Third request will consume the whole session window, which is now stalled.
        // A fourth request will not be able to receive data because the server is stalled.
        AtomicReference<Stream> streamRef3 = new AtomicReference<>();
        MetaData.Request request3 = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request3, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                // Do not read to stall flow control.
                streamRef3.set(stream);
            }
        });
        await().atMost(5, TimeUnit.SECONDS).until(() -> streamRef3.get() != null);

        // Fourth request is now stalled.
        AtomicReference<Stream> streamRef4 = new AtomicReference<>();
        MetaData.Request request4 = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request4, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                streamRef4.set(stream);
            }
        });
        // Verify that the data does not arrive because the server session is stalled.
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> streamRef4.get() == null);

        // Consume the data of the first response.
        // This will open up the session window, allowing the fourth stream to send data.
        consumeAll(streamRef1.get());
        await().atMost(5, TimeUnit.SECONDS).until(() -> streamRef4.get() != null);

        consumeAll(streamRef2.get());
        consumeAll(streamRef3.get());
        consumeAll(streamRef4.get());
    }

    private void consumeAll(Stream stream) throws Exception
    {
        await().pollInterval(1, TimeUnit.MILLISECONDS).atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Stream.Data data = stream.readData();
            if (data == null)
                return false;
            data.release();
            return data.frame().isEndStream();
        });
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testServerSendsBigContent(FlowControlStrategyType type) throws Exception
    {
        byte[] data = new byte[1024 * 1024];
        new Random().nextBytes(data);

        start(type, new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
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
                frame.getByteBuffer().get(bytes, received, remaining);
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

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testClientSendingInitialSmallWindow(FlowControlStrategyType type) throws Exception
    {
        start(type, new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData metaData = new MetaData.Response(200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
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
                    responseContent.put(data.frame().getByteBuffer());
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

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testClientExceedingSessionWindow(FlowControlStrategyType type) throws Exception
    {
        // On server, we don't consume the data.
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(type, new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // Do not read to stall the flow control.
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
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        ByteBuffer extraData = ByteBuffer.allocate(1024);
        http2Session.getGenerator().data(accumulator, new DataFrame(stream.getId(), extraData, true), extraData.remaining());
        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        http2Session.getEndPoint().write(Callback.NOOP, buffers.toArray(new ByteBuffer[0]));

        // Expect the connection to be closed.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testClientExceedingStreamWindow(FlowControlStrategyType type) throws Exception
    {
        // On server, we don't consume the data.
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(type, new ServerSessionListener()
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
                        // Do not read to stall the flow control.
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
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        ByteBuffer extraData = ByteBuffer.allocate(1024);
        http2Session.getGenerator().data(accumulator, new DataFrame(stream.getId(), extraData, true), extraData.remaining());
        List<ByteBuffer> buffers = accumulator.getByteBuffers();
        http2Session.getEndPoint().write(Callback.NOOP, buffers.toArray(new ByteBuffer[0]));

        // Expect the connection to be closed.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testFlowControlWhenServerResetsStream(FlowControlStrategyType type) throws Exception
    {
        // On server, don't consume the data and immediately reset.
        start(type, new ServerSessionListener()
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

    @ParameterizedTest
    @EnumSource(FlowControlStrategyType.class)
    public void testNoWindowUpdateForRemotelyClosedStream(FlowControlStrategyType type) throws Exception
    {
        start(type, new ServerSessionListener()
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
                        data.release();
                        boolean last = data.frame().isEndStream();
                        int status = last ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500;
                        MetaData.Response response = new MetaData.Response(status, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                    }
                };
            }
        });

        List<WindowUpdateFrame> sessionWindowUpdates = new ArrayList<>();
        List<WindowUpdateFrame> streamWindowUpdates = new ArrayList<>();
        client.setFlowControlStrategyFactory(() -> new SimpleFlowControlStrategy()
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

        // Write a small DATA frame so the server only performs 1 readData().
        ByteBuffer data = ByteBuffer.allocate(1);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        int sessionUpdates = switch (type)
        {
            case SIMPLE -> 1;
            // For small writes, session updates are buffered.
            case BUFFERING -> 0;
        };
        assertEquals(sessionUpdates, sessionWindowUpdates.size());
        assertEquals(0, streamWindowUpdates.size());
    }

    public enum FlowControlStrategyType
    {
        SIMPLE, BUFFERING
    }
}
