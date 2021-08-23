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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class InvocationTypeTest
{
    private Server server;
    private ServerConnector connector;
    private QueuedThreadPool serverThreads;

    private void start(Handler handler) throws Exception
    {
        // 1 acceptor, 1 selector, 1 reserved + 1 application.
        int maxThreads = 4;
        serverThreads = new QueuedThreadPool(maxThreads, maxThreads);
        serverThreads.setName("server");
        serverThreads.setReservedThreads(1);
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);
        HTTP2CServerConnectionFactory http2 = new HTTP2CServerConnectionFactory(new HttpConfiguration());
        connector = new ServerConnector(server, 1, 1, http2);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
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

        HTTP2Client client = new HTTP2Client();
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
        HttpURI uri1 = new HttpURI("http://localhost:" + connector.getLocalPort() + "/congest");
        MetaData.Request request1 = new MetaData.Request("GET", uri1, HttpVersion.HTTP_2, new HttpFields());
        session.newStream(new HeadersFrame(request1, null, true), new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                // Block here to stop reading from the network
                // to cause the server to TCP congest.
                awaitUntil(0, () -> clientBlockLatch.await(5, TimeUnit.SECONDS));
                callback.succeeded();
                if (frame.isEndStream())
                    clientDataLatch.countDown();
            }
        });

        awaitUntil(5000, () ->
        {
            AbstractEndPoint serverEndPoint = serverEndPointRef.get();
            return serverEndPoint != null && serverEndPoint.getWriteFlusher().isPending();
        });
        // Wait for NIO on the server to be OP_WRITE interested.
        Thread.sleep(1000);

        // Make sure there is a reserved thread.
        serverThreads.tryExecute(() ->
        {});
        awaitUntil(5000, () -> serverThreads.getAvailableReservedThreads() == 1);
        // Use the reserved thread for a blocking operation, simulating another blocking write.
        CountDownLatch serverBlockLatch = new CountDownLatch(1);
        assertTrue(serverThreads.tryExecute(() -> awaitUntil(0, () -> serverBlockLatch.await(15, TimeUnit.SECONDS))));

        // Unblock the client to read from the network, which should unblock the server write().
        clientBlockLatch.countDown();

        assertTrue(clientDataLatch.await(10, TimeUnit.SECONDS), server.dump());
        serverBlockLatch.countDown();
    }

    public void awaitUntil(long millis, Callable<Boolean> test)
    {
        try
        {
            if (millis == 0)
            {
                if (test.call())
                    return;
            }
            else
            {
                long begin = System.nanoTime();
                while (System.nanoTime() - begin < TimeUnit.MILLISECONDS.toNanos(millis))
                {
                    if (test.call())
                        return;
                    Thread.sleep(10);
                }
            }
            fail("Await elapsed: " + millis + "ms");
        }
        catch (RuntimeException | Error x)
        {
            throw x;
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }
}
