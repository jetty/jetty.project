//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BlockedWritesWithSmallThreadPoolTest
{
    private Server server;
    private ServerConnector connector;
    private QueuedThreadPool serverThreads;
    private HTTP2Client client;

    private void start(Handler handler) throws Exception
    {
        // Threads: 1 acceptor, 1 selector, 1 reserved, 1 application.
        serverThreads = newSmallThreadPool("server", 4);
        server = new Server(serverThreads);
        HTTP2CServerConnectionFactory http2 = new HTTP2CServerConnectionFactory(new HttpConfiguration());
        connector = new ServerConnector(server, 1, 1, http2);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private void start(RawHTTP2ServerConnectionFactory factory) throws Exception
    {
        // Threads: 1 acceptor, 1 selector, 1 reserved, 1 application.
        serverThreads = newSmallThreadPool("server", 4);
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1, factory);
        server.addConnector(connector);
        server.start();
    }

    private QueuedThreadPool newSmallThreadPool(String name, int maxThreads)
    {
        QueuedThreadPool pool = new QueuedThreadPool(maxThreads, maxThreads);
        pool.setName(name);
        pool.setReservedThreads(1);
        pool.setDetailedDump(true);
        return pool;
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testServerThreadsBlockedInWrites() throws Exception
    {
        int contentLength = 16 * 1024 * 1024;
        AtomicReference<AbstractEndPoint> serverEndPointRef = new AtomicReference<>();
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                serverEndPointRef.compareAndSet(null, (AbstractEndPoint)jettyRequest.getHttpChannel().getEndPoint());
                // Write a large content to cause TCP congestion.
                response.getOutputStream().write(new byte[contentLength]);
            }
        });

        client = new HTTP2Client();
        // Set large flow control windows so the server hits TCP congestion.
        int window = 2 * contentLength;
        client.setInitialSessionRecvWindow(window);
        client.setInitialStreamRecvWindow(window);
        client.start();

        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener.Adapter(), promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        CountDownLatch clientBlockLatch = new CountDownLatch(1);
        CountDownLatch clientDataLatch = new CountDownLatch(1);
        // Send a request to TCP congest the server.
        HttpURI uri = new HttpURI("http://localhost:" + connector.getLocalPort() + "/congest");
        MetaData.Request request = new MetaData.Request("GET", uri, HttpVersion.HTTP_2, new HttpFields());
        session.newStream(new HeadersFrame(request, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                try
                {
                    // Block here to stop reading from the network
                    // to cause the server to TCP congest.
                    clientBlockLatch.await(5, TimeUnit.SECONDS);
                    callback.succeeded();
                    if (frame.isEndStream())
                        clientDataLatch.countDown();
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            }
        });

        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            AbstractEndPoint serverEndPoint = serverEndPointRef.get();
            return serverEndPoint != null && serverEndPoint.getWriteFlusher().isPending();
        });
        // Wait for NIO on the server to be OP_WRITE interested.
        Thread.sleep(1000);

        // Make sure there is a reserved thread.
        if (serverThreads.getAvailableReservedThreads() != 1)
        {
            assertFalse(serverThreads.tryExecute(() -> {}));
            await().atMost(5, TimeUnit.SECONDS).until(() -> serverThreads.getAvailableReservedThreads() == 1);
        }
        // Use the reserved thread for a blocking operation, simulating another blocking write.
        CountDownLatch serverBlockLatch = new CountDownLatch(1);
        assertTrue(serverThreads.tryExecute(() -> await().atMost(20, TimeUnit.SECONDS).until(() -> serverBlockLatch.await(15, TimeUnit.SECONDS), b -> true)));

        assertEquals(0, serverThreads.getReadyThreads());

        // Unblock the client to read from the network, which should unblock the server write().
        clientBlockLatch.countDown();

        assertTrue(clientDataLatch.await(10, TimeUnit.SECONDS), server.dump());
        serverBlockLatch.countDown();
    }

    @Test
    public void testClientThreadsBlockedInWrite() throws Exception
    {
        int contentLength = 16 * 1024 * 1024;
        CountDownLatch serverBlockLatch = new CountDownLatch(1);
        RawHTTP2ServerConnectionFactory http2 = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        try
                        {
                            // Block here to stop reading from the network
                            // to cause the client to TCP congest.
                            serverBlockLatch.await(5, TimeUnit.SECONDS);
                            callback.succeeded();
                            if (frame.isEndStream())
                            {
                                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
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
        int window = 2 * contentLength;
        http2.setInitialSessionRecvWindow(window);
        http2.setInitialStreamRecvWindow(window);
        start(http2);

        client = new HTTP2Client();
        // Threads: 1 selector, 1 reserved, 1 application.
        QueuedThreadPool clientThreads = newSmallThreadPool("client", 3);
        client.setExecutor(clientThreads);
        client.setSelectors(1);
        client.start();

        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Listener.Adapter(), promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        // Send a request to TCP congest the client.
        HttpURI uri = new HttpURI("http://localhost:" + connector.getLocalPort() + "/congest");
        MetaData.Request request = new MetaData.Request("GET", uri, HttpVersion.HTTP_2, new HttpFields());
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(contentLength), true), Callback.NOOP);

        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            AbstractEndPoint clientEndPoint = (AbstractEndPoint)((HTTP2Session)session).getEndPoint();
            return clientEndPoint.getWriteFlusher().isPending();
        });
        // Wait for NIO on the client to be OP_WRITE interested.
        Thread.sleep(1000);

        CountDownLatch clientBlockLatch = new CountDownLatch(1);
        // Make sure the application thread is blocked.
        clientThreads.execute(() -> await().until(() -> clientBlockLatch.await(15, TimeUnit.SECONDS), b -> true));
        // Make sure the reserved thread is blocked.
        if (clientThreads.getAvailableReservedThreads() != 1)
        {
            assertFalse(clientThreads.tryExecute(() -> {}));
            await().atMost(5, TimeUnit.SECONDS).until(() -> clientThreads.getAvailableReservedThreads() == 1);
        }
        // Use the reserved thread for a blocking operation, simulating another blocking write.
        assertTrue(clientThreads.tryExecute(() -> await().until(() -> clientBlockLatch.await(15, TimeUnit.SECONDS), b -> true)));

        await().atMost(5, TimeUnit.SECONDS).until(() -> clientThreads.getReadyThreads() == 0);

        // Unblock the server to read from the network, which should unblock the client.
        serverBlockLatch.countDown();

        assertTrue(latch.await(10, TimeUnit.SECONDS), client.dump());
        clientBlockLatch.countDown();
    }
}
