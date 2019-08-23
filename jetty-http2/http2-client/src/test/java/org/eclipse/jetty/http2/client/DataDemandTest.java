//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class DataDemandTest extends AbstractTest
{
    @Test
    public void testExplicitDemand() throws Exception
    {
        int length = FlowControlStrategy.DEFAULT_WINDOW_SIZE - 1;
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        Queue<DataFrame> serverQueue = new ConcurrentLinkedQueue<>();
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverStreamRef.set(stream);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onDataRequested(Stream stream, DataFrame frame, Callback callback)
                    {
                        // Don't demand and don't complete callbacks.
                        serverQueue.offer(frame);
                    }
                };
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request post = newRequest("POST", new HttpFields());
        FuturePromise<Stream> promise = new FuturePromise<>();
        Queue<DataFrame> clientQueue = new ConcurrentLinkedQueue<>();
        client.newStream(new HeadersFrame(post, null, false), promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onDataRequested(Stream stream, DataFrame frame, Callback callback)
            {
                clientQueue.offer(frame);
            }
        });
        Stream clientStream = promise.get(5, TimeUnit.SECONDS);
        // Send a single frame larger than the default frame size,
        // so that it will be split on the server in multiple frames.
        clientStream.data(new DataFrame(clientStream.getId(), ByteBuffer.allocate(length), true), Callback.NOOP);

        // The server should receive only 1 DATA frame because it does explicit demand.
        // Wait a bit more to be sure it only receives 1 DATA frame.
        Thread.sleep(1000);
        assertEquals(1, serverQueue.size());

        Stream serverStream = serverStreamRef.get();
        assertNotNull(serverStream);

        // Demand more DATA frames.
        int count = 2;
        serverStream.demand(count);
        Thread.sleep(1000);
        // The server should have received `count` more DATA frames.
        assertEquals(1 + count, serverQueue.size());

        // Demand all the rest.
        serverStream.demand(Long.MAX_VALUE);
        int loops = 0;
        while (true)
        {
            if (++loops > 100)
                fail();

            Thread.sleep(100);

            long sum = serverQueue.stream()
                .mapToLong(frame -> frame.getData().remaining())
                .sum();
            if (sum == length)
                break;
        }

        // Even if demanded, the flow control window should not have
        // decreased because the callbacks have not been completed.
        int recvWindow = ((ISession)serverStream.getSession()).updateRecvWindow(0);
        assertEquals(FlowControlStrategy.DEFAULT_WINDOW_SIZE - length, recvWindow);

        // Send a large DATA frame to the client.
        serverStream.data(new DataFrame(serverStream.getId(), ByteBuffer.allocate(length), true), Callback.NOOP);


        // The client should receive only 1 DATA frame because it does explicit demand.
        // Wait a bit more to be sure it only receives 1 DATA frame.
        Thread.sleep(1000);
        assertEquals(1, clientQueue.size());

        // Demand more DATA frames.
        clientStream.demand(count);
        Thread.sleep(1000);
        // The client should have received `count` more DATA frames.
        assertEquals(1 + count, clientQueue.size());

        // Demand all the rest.
        clientStream.demand(Long.MAX_VALUE);
        loops = 0;
        while (true)
        {
            if (++loops > 100)
                fail();

            Thread.sleep(100);

            long sum = clientQueue.stream()
                .mapToLong(frame -> frame.getData().remaining())
                .sum();
            if (sum == length)
                break;
        }

        // Both the client and server streams should be gone now.
        assertNull(clientStream.getSession().getStream(clientStream.getId()));
        assertNull(serverStream.getSession().getStream(serverStream.getId()));
    }

    @Test
    public void testSynchronousDemandDoesNotStackOverflow() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onDataRequested(Stream stream, DataFrame frame, Callback callback)
                    {
                        stream.demand(1);
                        callback.succeeded();
                        if (frame.isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        }
                    }
                };
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request post = newRequest("POST", new HttpFields());
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        client.newStream(new HeadersFrame(post, null, false), promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    latch.countDown();
                }
            }
        });
        Stream clientStream = promise.get(5, TimeUnit.SECONDS);

        // Generate a lot of small DATA frames and write them in a single
        // write so that the server will continuously be notified and demand,
        // which will test that it won't throw StackOverflowError.
        MappedByteBufferPool byteBufferPool = new MappedByteBufferPool();
        Generator generator = new Generator(byteBufferPool);
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        for (int i = 512; i >= 0; --i)
            generator.data(lease, new DataFrame(clientStream.getId(), ByteBuffer.allocate(1), i == 0), 1);
        ((HTTP2Session)clientStream.getSession()).getEndPoint().write(Callback.NOOP, lease.getByteBuffers().toArray(new ByteBuffer[0]));

        assertTrue(latch.await(15, TimeUnit.SECONDS));
    }
}
