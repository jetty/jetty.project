//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.SimpleFlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

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
        connector = new ServerConnector(server, new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener)
        {
            @Override
            protected FlowControlStrategy newFlowControlStrategy()
            {
                return FlowControlStrategyTest.this.newFlowControlStrategy();
            }
        });
        server.addConnector(connector);
        server.start();

        client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.setClientConnectionFactory(new HTTP2ClientConnectionFactory()
        {
            @Override
            protected FlowControlStrategy newFlowControlStrategy()
            {
//                return FlowControlStrategyTest.this.newFlowControlStrategy();
                return new SimpleFlowControlStrategy();
            }
        });
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
        return new MetaData.Request(method, HttpScheme.HTTP, new HostPortHttpField(authority), "/", HttpVersion.HTTP_2, fields);
    }

    @After
    public void dispose() throws Exception
    {
        // Allow WINDOW_UPDATE frames to be sent/received to avoid exception stack traces.
        Thread.sleep(1000);
        client.stop();
        server.stop();
    }

    @Test
    public void testFlowControlWithConcurrentSettings() throws Exception
    {
        // Initial window is 64 KiB. We allow the client to send 1024 B
        // then we change the window to 512 B. At this point, the client
        // must stop sending data (although the initial window allows it).

        final int size = 512;
        // We get 3 data frames: the first of 1024 and 2 of 512 each
        // after the flow control window has been reduced.
        final CountDownLatch dataLatch = new CountDownLatch(3);
        final AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                HttpFields fields = new HttpFields();
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, fields);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);

                return new Stream.Listener.Adapter()
                {
                    private final AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        dataLatch.countDown();
                        int dataFrameCount = dataFrames.incrementAndGet();
                        if (dataFrameCount == 1)
                        {
                            callbackRef.set(callback);
                            Map<Integer, Integer> settings = new HashMap<>();
                            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, size);
                            stream.getSession().settings(new SettingsFrame(settings, false), Callback.Adapter.INSTANCE);
                            // Do not succeed the callback here.
                        }
                        else if (dataFrameCount > 1)
                        {
                            // Consume the data.
                            callback.succeeded();
                        }
                    }
                };
            }
        });

        // Two SETTINGS frames, the initial one and the one we send from the server.
        final CountDownLatch settingsLatch = new CountDownLatch(2);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        MetaData.Request request = newRequest("POST", new HttpFields());
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(new HeadersFrame(0, request, null, false), promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Send first chunk that exceeds the window.
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(size * 2), false), Callback.Adapter.INSTANCE);
        settingsLatch.await(5, TimeUnit.SECONDS);

        // Send the second chunk of data, must not arrive since we're flow control stalled on the client.
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(size * 2), true), Callback.Adapter.INSTANCE);
        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        // Consume the data arrived to server, this will resume flow control on the client.
        callbackRef.get().succeeded();

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerFlowControlOneBigWrite() throws Exception
    {
        final int windowSize = 1536;
        final int length = 5 * windowSize;
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);

                DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(length), true);
                stream.data(dataFrame, Callback.Adapter.INSTANCE);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, windowSize);
        session.settings(new SettingsFrame(settings, false), Callback.Adapter.INSTANCE);

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch dataLatch = new CountDownLatch(1);
        final Exchanger<Callback> exchanger = new Exchanger<>();
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            private AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                try
                {
                    int dataFrames = this.dataFrames.incrementAndGet();
                    if (dataFrames == 1 || dataFrames == 2)
                    {
                        // Do not consume the data frame.
                        // We should then be flow-control stalled.
                        exchanger.exchange(callback);
                    }
                    else if (dataFrames == 3 || dataFrames == 4 || dataFrames == 5)
                    {
                        // Consume totally.
                        callback.succeeded();
                        if (frame.isEndStream())
                            dataLatch.countDown();
                    }
                    else
                    {
                        Assert.fail();
                    }
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            }
        });

        Callback callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the first chunk.
        callback.succeeded();

        callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the second chunk.
        callback.succeeded();

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientFlowControlOneBigWrite() throws Exception
    {
        final int windowSize = 1536;
        final Exchanger<Callback> exchanger = new Exchanger<>();
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
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
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return new Stream.Listener.Adapter()
                {
                    private AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        try
                        {
                            int dataFrames = this.dataFrames.incrementAndGet();
                            if (dataFrames == 1 || dataFrames == 2)
                            {
                                // Do not consume the data frame.
                                // We should then be flow-control stalled.
                                exchanger.exchange(callback);
                            }
                            else if (dataFrames == 3 || dataFrames == 4 || dataFrames == 5)
                            {
                                // Consume totally.
                                callback.succeeded();
                                if (frame.isEndStream())
                                    dataLatch.countDown();
                            }
                            else
                            {
                                Assert.fail();
                            }
                        }
                        catch (InterruptedException x)
                        {
                            callback.failed(x);
                        }
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, null);
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        final int length = 5 * windowSize;
        DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(length), true);
        stream.data(dataFrame, Callback.Adapter.INSTANCE);

        Callback callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the first chunk.
        callback.succeeded();

        callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the second chunk.
        callback.succeeded();

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    private void checkThatWeAreFlowControlStalled(Exchanger<Callback> exchanger) throws Exception
    {
        try
        {
            exchanger.exchange(null, 1, TimeUnit.SECONDS);
        }
        catch (TimeoutException x)
        {
            // Expected.
        }
    }

    @Test
    public void testSessionStalledStallsNewStreams() throws Exception
    {
        final int windowSize = 1024;
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Request request = (MetaData.Request)requestFrame.getMetaData();
                if ("POST".equalsIgnoreCase(request.getMethod()))
                {
                    // Send data to consume the session window.
                    ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE - windowSize);
                    DataFrame dataFrame = new DataFrame(stream.getId(), data, true);
                    stream.data(dataFrame, Callback.Adapter.INSTANCE);
                    return null;
                }
                else
                {
                    // For every stream, send down half the window size of data.
                    MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                    HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                    stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                    DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(windowSize / 2), true);
                    stream.data(dataFrame, Callback.Adapter.INSTANCE);
                    return null;
                }
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        // First request is just to consume the session window.
        final CountDownLatch prepareLatch = new CountDownLatch(1);
        MetaData.Request request1 = newRequest("POST", new HttpFields());
        session.newStream(new HeadersFrame(0, request1, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                // Do not consume the data to reduce the session window.
                if (frame.isEndStream())
                    prepareLatch.countDown();
            }
        });
        Assert.assertTrue(prepareLatch.await(5, TimeUnit.SECONDS));

        final AtomicReference<Callback> callbackRef2 = new AtomicReference<>();
        final AtomicReference<Callback> callbackRef3 = new AtomicReference<>();

        // Second request will consume half the session window.
        MetaData.Request request2 = newRequest("GET", new HttpFields());
        session.newStream(new HeadersFrame(0, request2, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                // Do not consume it to stall flow control.
                callbackRef2.set(callback);
            }
        });

        // Third request will consume the session window, which is now stalled.
        // A fourth request will not be able to receive data.
        MetaData.Request request3 = newRequest("GET", new HttpFields());
        session.newStream(new HeadersFrame(0, request3, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                // Do not consume it to stall flow control.
                callbackRef3.set(callback);
            }
        });

        // Fourth request is now stalled.
        final CountDownLatch latch = new CountDownLatch(1);
        MetaData.Request request4 = newRequest("GET", new HttpFields());
        session.newStream(new HeadersFrame(0, request4, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        // Verify that the data does not arrive because the server session is stalled.
        Assert.assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Consume the data of the third response.
        // This will open up the session window, allowing the third stream to send data.
        Callback callback2 = callbackRef3.getAndSet(null);
        if (callback2 != null)
            callback2.succeeded();

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSendsBigContent() throws Exception
    {
        final byte[] data = new byte[1024 * 1024];
        new Random().nextBytes(data);

        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.wrap(data), true);
                stream.data(dataFrame, Callback.Adapter.INSTANCE);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        final byte[] bytes = new byte[data.length];
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            private int received;

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                int remaining = frame.remaining();
                frame.getData().get(bytes, received, remaining);
                this.received += remaining;
                callback.succeeded();
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(15, TimeUnit.SECONDS));
        Assert.assertArrayEquals(data, bytes);
    }

    @Test
    public void testServerTwoDataFramesWithStalledSession() throws Exception
    {
        // Frames in queue = DATA1, DATA2.
        // Server writes part of DATA1, then stalls.
        // A window update unstalls the session, verify that the data is correctly sent.

        Random random = new Random();
        final byte[] chunk1 = new byte[1024];
        random.nextBytes(chunk1);
        final byte[] chunk2 = new byte[1024];
        random.nextBytes(chunk2);

        final AtomicReference<CountDownLatch> settingsLatch = new AtomicReference<>(new CountDownLatch(1));
        final CountDownLatch dataLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.get().countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(chunk1), false), Callback.Adapter.INSTANCE);
                stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(chunk2), true), Callback.Adapter.INSTANCE);
                dataLatch.countDown();
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, 0);
        session.settings(new SettingsFrame(settings, false), Callback.Adapter.INSTANCE);
        Assert.assertTrue(settingsLatch.get().await(5, TimeUnit.SECONDS));

        byte[] content = new byte[chunk1.length + chunk2.length];
        final ByteBuffer buffer = ByteBuffer.wrap(content);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        final CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(requestFrame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                buffer.put(frame.getData());
                callback.succeeded();
                if (frame.isEndStream())
                    responseLatch.countDown();
            }
        });
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        // Now we have the 2 DATA frames queued in the server.

        // Partially unstall the first DATA frame.
        settingsLatch.set(new CountDownLatch(1));
        settings.clear();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, chunk1.length / 2);
        session.settings(new SettingsFrame(settings, false), Callback.Adapter.INSTANCE);
        Assert.assertTrue(settingsLatch.get().await(5, TimeUnit.SECONDS));

        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSendingInitialSmallWindow() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        // Since we echo back the data
                        // asynchronously we must copy it.
                        ByteBuffer data = frame.getData();
                        ByteBuffer copy = ByteBuffer.allocateDirect(data.remaining());
                        copy.put(data).flip();
                        stream.data(new DataFrame(stream.getId(), copy, frame.isEndStream()), callback);
                    }
                };
            }
        });

        final int initialWindow = 16;
        Session session = newClient(new Session.Listener.Adapter()
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
        final ByteBuffer responseContent = ByteBuffer.wrap(responseData);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                responseContent.put(frame.getData());
                callback.succeeded();
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        ByteBuffer requestContent = ByteBuffer.wrap(requestData);
        DataFrame dataFrame = new DataFrame(stream.getId(), requestContent, true);
        stream.data(dataFrame, Callback.Adapter.INSTANCE);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        responseContent.flip();
        Assert.assertArrayEquals(requestData, responseData);
    }

    @Test
    public void testClientExceedingSessionWindow() throws Exception
    {
        // On server, we don't consume the data.
        start(new ServerSessionListener.Adapter());

        final CountDownLatch closeLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                if (frame.getError() == ErrorCode.FLOW_CONTROL_ERROR.code)
                    closeLatch.countDown();
            }
        });

        // Consume the whole session and stream window.
        MetaData.Request metaData = newRequest("POST", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, new Stream.Listener.Adapter());
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, false), new Callback.Adapter()
        {
            @Override
            public void succeeded()
            {
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));


        // Now the client is supposed to not send more frames.
        // If it does, the connection must be closed.
        HTTP2Session http2Session = (HTTP2Session)session;
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(connector.getByteBufferPool());
        ByteBuffer extraData = ByteBuffer.allocate(1024);
        http2Session.getGenerator().data(lease, new DataFrame(stream.getId(), extraData, true), extraData.remaining());
        List<ByteBuffer> buffers = lease.getByteBuffers();
        http2Session.getEndPoint().write(Callback.Adapter.INSTANCE, buffers.toArray(new ByteBuffer[buffers.size()]));

        // Expect the connection to be closed.
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientExceedingStreamWindow() throws Exception
    {
        // On server, we don't consume the data.
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                // Enlarge the session window.
                ((ISession)session).updateRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
                return super.onPreface(session);
            }
        });

        final CountDownLatch closeLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                if (frame.getError() == ErrorCode.FLOW_CONTROL_ERROR.code)
                    closeLatch.countDown();
            }
        });

        // Consume the whole stream window.
        MetaData.Request metaData = newRequest("POST", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, new Stream.Listener.Adapter());
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, false), new Callback.Adapter()
        {
            @Override
            public void succeeded()
            {
                dataLatch.countDown();
            }
        });
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        // Wait for a while to allow flow control window frames
        // to be exchanged before doing the "sneaky" write below.
        Thread.sleep(1000);

        // Now the client is supposed to not send more frames.
        // If it does, the connection must be closed.
        HTTP2Session http2Session = (HTTP2Session)session;
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(connector.getByteBufferPool());
        ByteBuffer extraData = ByteBuffer.allocate(1024);
        http2Session.getGenerator().data(lease, new DataFrame(stream.getId(), extraData, true), extraData.remaining());
        List<ByteBuffer> buffers = lease.getByteBuffers();
        http2Session.getEndPoint().write(Callback.Adapter.INSTANCE, buffers.toArray(new ByteBuffer[buffers.size()]));

        // Expect the connection to be closed.
        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}
