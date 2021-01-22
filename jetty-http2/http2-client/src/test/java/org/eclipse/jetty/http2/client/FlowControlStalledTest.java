//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.BufferingFlowControlStrategy;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlowControlStalledTest
{
    protected ServerConnector connector;
    protected HTTP2Client client;
    protected Server server;

    protected void start(FlowControlStrategy.Factory flowControlFactory, ServerSessionListener listener) throws Exception
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        RawHTTP2ServerConnectionFactory connectionFactory = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener);
        connectionFactory.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connectionFactory.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connectionFactory.setFlowControlStrategyFactory(flowControlFactory);
        connector = new ServerConnector(server, connectionFactory);
        server.addConnector(connector);
        server.start();

        client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        client.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        client.setFlowControlStrategyFactory(flowControlFactory);
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

    protected MetaData.Request newRequest(String method, String target, HttpFields fields)
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        return new MetaData.Request(method, HttpScheme.HTTP.asString(), new HostPortHttpField(authority), target, HttpVersion.HTTP_2, fields, -1);
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
    public void testStreamStalledIsInvokedOnlyOnce() throws Exception
    {
        AtomicReference<CountDownLatch> stallLatch = new AtomicReference<>(new CountDownLatch(1));
        CountDownLatch unstallLatch = new CountDownLatch(1);
        start(() -> new BufferingFlowControlStrategy(0.5f)
        {
            @Override
            public void onStreamStalled(IStream stream)
            {
                super.onStreamStalled(stream);
                stallLatch.get().countDown();
            }

            @Override
            protected void onStreamUnstalled(IStream stream)
            {
                super.onStreamUnstalled(stream);
                unstallLatch.countDown();
            }
        }, new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);

                if (request.getURIString().endsWith("/stall"))
                {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, false), new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            // Send a large chunk of data so the stream gets stalled.
                            ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE + 1);
                            stream.data(new DataFrame(stream.getId(), data, true), NOOP);
                        }
                    });
                }
                else
                {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                }

                return null;
            }
        });

        // Use a large session window so that only the stream gets stalled.
        client.setInitialSessionRecvWindow(5 * FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        Session client = newClient(new Session.Listener.Adapter());

        CountDownLatch latch = new CountDownLatch(1);
        Queue<Callback> callbacks = new ArrayDeque<>();
        MetaData.Request request = newRequest("GET", "/stall", HttpFields.EMPTY);
        client.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callbacks.offer(callback);
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(stallLatch.get().await(5, TimeUnit.SECONDS));

        // First stream is now stalled, check that writing a second stream
        // does not result in the first be notified again of being stalled.
        stallLatch.set(new CountDownLatch(1));

        request = newRequest("GET", "/", HttpFields.EMPTY);
        client.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter());

        assertFalse(stallLatch.get().await(1, TimeUnit.SECONDS));

        // Consume all data.
        while (!latch.await(10, TimeUnit.MILLISECONDS))
        {
            Callback callback = callbacks.poll();
            if (callback != null)
                callback.succeeded();
        }

        // Make sure the unstall callback is invoked.
        assertTrue(unstallLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSessionStalledIsInvokedOnlyOnce() throws Exception
    {
        AtomicReference<CountDownLatch> stallLatch = new AtomicReference<>(new CountDownLatch(1));
        CountDownLatch unstallLatch = new CountDownLatch(1);
        start(() -> new BufferingFlowControlStrategy(0.5f)
        {
            @Override
            public void onSessionStalled(ISession session)
            {
                super.onSessionStalled(session);
                stallLatch.get().countDown();
            }

            @Override
            protected void onSessionUnstalled(ISession session)
            {
                super.onSessionUnstalled(session);
                unstallLatch.countDown();
            }
        }, new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);

                if (request.getURIString().endsWith("/stall"))
                {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, false), new Callback()
                    {
                        @Override
                        public void succeeded()
                        {
                            // Send a large chunk of data so the session gets stalled.
                            ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE + 1);
                            stream.data(new DataFrame(stream.getId(), data, true), NOOP);
                        }
                    });
                }
                else
                {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                }

                return null;
            }
        });

        // Use a large stream window so that only the session gets stalled.
        client.setInitialStreamRecvWindow(5 * FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, client.getInitialStreamRecvWindow());
                return settings;
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        Queue<Callback> callbacks = new ArrayDeque<>();
        MetaData.Request request = newRequest("GET", "/stall", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callbacks.offer(callback);
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(stallLatch.get().await(5, TimeUnit.SECONDS));

        // The session is now stalled, check that writing a second stream
        // does not result in the session be notified again of being stalled.
        stallLatch.set(new CountDownLatch(1));

        request = newRequest("GET", "/", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter());

        assertFalse(stallLatch.get().await(1, TimeUnit.SECONDS));

        // Consume all data.
        while (!latch.await(10, TimeUnit.MILLISECONDS))
        {
            Callback callback = callbacks.poll();
            if (callback != null)
                callback.succeeded();
        }

        // Make sure the unstall callback is invoked.
        assertTrue(unstallLatch.await(5, TimeUnit.SECONDS));
    }
}
