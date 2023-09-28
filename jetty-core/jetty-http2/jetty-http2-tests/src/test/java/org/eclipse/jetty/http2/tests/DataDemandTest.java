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

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DataDemandTest extends AbstractTest
{
    @Test
    public void testExplicitDemand() throws Exception
    {
        int length = FlowControlStrategy.DEFAULT_WINDOW_SIZE - 1;
        AtomicReference<Stream> serverStreamRef = new AtomicReference<>();
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(stream::demand));
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // Don't read and don't demand.
                        serverStreamRef.set(stream);
                    }
                };
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request post = newRequest("POST", HttpFields.EMPTY);
        AtomicReference<Stream> clientStreamRef = new AtomicReference<>();
        client.newStream(new HeadersFrame(post, null, false), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                // Don't read and don't demand.
                clientStreamRef.set(stream);
            }
        }).thenCompose(s ->
        {
            // Send a single frame larger than the default frame size,
            // so that it will be split on the server in multiple frames.
            return s.data(new DataFrame(s.getId(), ByteBuffer.allocate(length), true));
        });

        // The server onDataAvailable() should be invoked once because it does one explicit demand.
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() != null);
        Stream serverStream = serverStreamRef.getAndSet(null);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() == null);

        // Read and demand 1 more DATA frame.
        Stream.Data data = serverStream.readData();
        assertNotNull(data);
        AtomicInteger serverReceived = new AtomicInteger(data.frame().remaining());
        data.release();
        serverStream.demand();

        // The server onDataAvailable() should be invoked.
        await().atMost(5, TimeUnit.SECONDS).until(() -> serverStreamRef.get() != null);

        // Read all the rest.
        await().pollInterval(1, TimeUnit.MILLISECONDS).atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Stream.Data d = serverStream.readData();
            if (d == null)
                return false;
            serverReceived.addAndGet(d.frame().remaining());
            d.release();
            return d.frame().isEndStream();
        });
        assertEquals(length, serverReceived.get());

        // Send a large DATA frame to the client.
        serverStream.data(new DataFrame(serverStream.getId(), ByteBuffer.allocate(length), true));

        // The client onDataAvailable() should be invoked once because it does one explicit demand.
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientStreamRef.get() != null);
        Stream clientStream = clientStreamRef.getAndSet(null);
        await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> clientStreamRef.get() == null);

        // Read and demand 1 more DATA frame.
        data = clientStream.readData();
        assertNotNull(data);
        AtomicInteger clientReceived = new AtomicInteger(data.frame().remaining());
        data.release();
        clientStream.demand();

        // The client onDataAvailable() should be invoked.
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientStreamRef.get() != null);

        // Read all the rest.
        await().pollInterval(1, TimeUnit.MILLISECONDS).atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Stream.Data d = clientStream.readData();
            if (d == null)
                return false;
            clientReceived.addAndGet(d.frame().remaining());
            d.release();
            return d.frame().isEndStream();
        });
        assertEquals(length, clientReceived.get());

        // Both the client and server streams should be gone now.
        assertNull(clientStream.getSession().getStream(clientStream.getId()));
        assertNull(serverStream.getSession().getStream(serverStream.getId()));
    }

    @Test
    public void testNoDemandNoOnDataAvailable() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(() -> sendData(stream), x -> {}));
                return null;
            }

            private void sendData(Stream stream)
            {
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1024 * 1024), true), Callback.NOOP);
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request post = newRequest("GET", HttpFields.EMPTY);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        client.newStream(new HeadersFrame(post, null, true), promise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                responseLatch.countDown();
                // Don't demand.
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                assertNotNull(data);
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        });
        Stream clientStream = promise.get(5, TimeUnit.SECONDS);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        // Should not receive DATA frames until demanded.
        assertFalse(latch.await(1, TimeUnit.SECONDS));
        // Now demand the first DATA frame.
        clientStream.demand();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDemandFromOnHeaders() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(() -> sendData(stream), x -> {}));
                return null;
            }

            private void sendData(Stream stream)
            {
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1024 * 1024), true), Callback.NOOP);
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request post = newRequest("GET", HttpFields.EMPTY);
        CountDownLatch latch = new CountDownLatch(1);
        client.newStream(new HeadersFrame(post, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                assertNotNull(data);
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDemandFromOnHeadersDoesNotInvokeOnDataAvailable() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(() -> sendData(stream), x -> {}));
                return null;
            }

            private void sendData(Stream stream)
            {
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1024 * 1024), true), Callback.NOOP);
            }
        });

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request post = newRequest("GET", HttpFields.EMPTY);
        CountDownLatch latch = new CountDownLatch(1);
        client.newStream(new HeadersFrame(post, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            private boolean inHeaders;

            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                inHeaders = true;
                stream.demand();
                inHeaders = false;
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                assertFalse(inHeaders);
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSynchronousDemandDoesNotStackOverflow() throws Exception
    {
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
                        data.release();
                        if (data.frame().isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
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

        Session client = newClientSession(new Session.Listener() {});
        MetaData.Request post = newRequest("POST", HttpFields.EMPTY);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        client.newStream(new HeadersFrame(post, null, false), promise, new Stream.Listener()
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
        ByteBufferPool bufferPool = new ArrayByteBufferPool();
        Generator generator = new Generator(bufferPool);
        ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
        for (int i = 512; i >= 0; --i)
            generator.data(accumulator, new DataFrame(clientStream.getId(), ByteBuffer.allocate(1), i == 0), 1);

        // Since this is a naked write, we need to wait that the
        // client finishes writing the SETTINGS reply to the server
        // during connection initialization, or we risk a WritePendingException.
        Thread.sleep(1000);
        ((HTTP2Session)clientStream.getSession()).getEndPoint().write(Callback.NOOP, accumulator.getByteBuffers().toArray(ByteBuffer[]::new));

        assertTrue(latch.await(15, TimeUnit.SECONDS));
    }
}
