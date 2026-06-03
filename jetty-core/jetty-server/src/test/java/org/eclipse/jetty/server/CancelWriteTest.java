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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritePendingException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CancelWriteTest
{
    private Server server;
    private ArrayByteBufferPool.Tracking bufferPool;

    @BeforeEach
    public void setUp()
    {
        bufferPool = new ArrayByteBufferPool.Tracking();
        server = new Server(null, null, bufferPool);
    }

    @AfterEach
    public void tearDown()
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Fails on Windows")
    public void testCancelCongestedWrite() throws Exception
    {
        long idleTimeout = 1000;
        CountDownLatch serverWriteFailureLatch = new CountDownLatch(1);

        ServerConnector connector = new ServerConnector(server, 1, 1);
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                SocketChannelEndPoint serverEndPoint = (SocketChannelEndPoint)request.getConnectionMetaData().getConnection().getEndPoint();

                // Large write, it blocks due to TCP congestion.
                RetainableByteBuffer.Mutable buffer = server.getByteBufferPool().acquire(128 * 1024 * 1024, true);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                byteBuffer.clear();
                response.write(true, byteBuffer, Callback.from(callback::succeeded, x ->
                {
                    // Check that the WriteFlusher won't access the
                    // buffer anymore, so that it can be released.
                    if (serverEndPoint.getWriteFlusher().isFailed())
                        serverWriteFailureLatch.countDown();

                    // Release the buffer.
                    buffer.release();

                    // Complete the Handler callback.
                    callback.failed(x);
                }));
                return true;
            }
        });
        server.start();

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            HttpTester.Request request = new HttpTester.Request();
            request.setMethod("GET");
            request.setHeader("Host", "localhost");
            request.setURI("/");
            ByteBuffer buffer = request.generate();

            client.write(buffer);

            assertTrue(serverWriteFailureLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

            // Verify that the server eventually closes the connection.
            long totalRead = 0L;
            client.socket().setSoTimeout(1000);
            ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
            while (true)
            {
                byteBuffer.clear();
                int read = client.read(byteBuffer);
                if (read < 0)
                    break;
                totalRead += read;
            }
            assertThat(totalRead, greaterThan(0L));
        }
    }

    @Test
    public void testCancelBeforeWrite() throws Exception
    {
        CountDownLatch serverWriteFailureLatch = new CountDownLatch(1);
        CountDownLatch serverEndPointWriteFailureLatch = new CountDownLatch(1);
        ServerConnector connector = new ServerConnector(server, 1, 1)
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                SocketChannelEndPoint endpoint = new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    public void write(ReadableBuffer buffer, Callback callback) throws WritePendingException
                    {
                        HttpConnection connection = (HttpConnection)getConnection();
                        Runnable runnable = connection.getHttpChannel().onFailure(new ArithmeticException());
                        Thread thread = new Thread(runnable);
                        thread.start();

                        // Wait until the thread running the failure runnable cancelled the write on the endpoint.
                        await().atMost(5, TimeUnit.SECONDS).until(() -> getWriteFlusher().isFailed());

                        super.write(buffer, Callback.from(callback::succeeded, x ->
                        {
                            if (serverWriteFailureLatch.getCount() == 1L)
                                serverEndPointWriteFailureLatch.countDown();

                            // Complete the send callback from HttpConnection.
                            callback.failed(x);
                        }));
                    }
                };
                endpoint.setIdleTimeout(getIdleTimeout());
                return endpoint;
            }
        };
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                RetainableByteBuffer.Mutable buffer = server.getByteBufferPool().acquire(1024, true);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                byteBuffer.clear();
                response.write(true, byteBuffer, Callback.from(callback::succeeded, x ->
                {
                    if (serverEndPointWriteFailureLatch.getCount() == 0L)
                        serverWriteFailureLatch.countDown();

                    // Release the buffer.
                    buffer.release();

                    // Complete the Handler callback.
                    callback.failed(x);
                }));
                return true;
            }
        });
        server.start();

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            HttpTester.Request request = new HttpTester.Request();
            request.setMethod("GET");
            request.setHeader("Host", "localhost");
            request.setURI("/");
            ByteBuffer buffer = request.generate();

            client.write(buffer);

            assertTrue(serverWriteFailureLatch.await(5, TimeUnit.SECONDS));
            assertTrue(serverEndPointWriteFailureLatch.await(5, TimeUnit.SECONDS));

            // Verify that the server eventually closes the connection after writing 0 byte.
            long totalRead = 0L;
            client.socket().setSoTimeout(1000);
            ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
            while (true)
            {
                byteBuffer.clear();
                int read = client.read(byteBuffer);
                if (read < 0)
                    break;
                totalRead += read;
            }
            assertThat(totalRead, is(0L));
        }
    }

    @Test
    public void testCancelAfterWrite() throws Exception
    {
        CountDownLatch serverEndPointWriteSuccessLatch = new CountDownLatch(1);
        CountDownLatch serverWriteFailureLatch = new CountDownLatch(1);
        CountDownLatch serverWriteSuccessLatch = new CountDownLatch(1);
        ServerConnector connector = new ServerConnector(server, 1, 1)
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
            {
                SocketChannelEndPoint endpoint = new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    public void write(ReadableBuffer buffer, Callback callback) throws WritePendingException
                    {
                        super.write(buffer, Callback.from(() ->
                        {
                            if (serverWriteFailureLatch.getCount() == 1L)
                                serverEndPointWriteSuccessLatch.countDown();

                            HttpConnection connection = (HttpConnection)getConnection();
                            Runnable runnable = connection.getHttpChannel().onFailure(new ArithmeticException());
                            Thread thread = new Thread(runnable);
                            thread.start();

                            // Wait until the thread running the failure runnable cancelled the write on the endpoint.
                            await().atMost(5, TimeUnit.SECONDS).until(() -> getWriteFlusher().isFailed());

                            callback.succeeded();
                        }, callback::failed));
                    }
                };
                endpoint.setIdleTimeout(getIdleTimeout());
                return endpoint;
            }
        };
        server.addConnector(connector);
        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                RetainableByteBuffer.Mutable buffer = server.getByteBufferPool().acquire(1024, true);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                byteBuffer.clear();
                response.write(true, byteBuffer, Callback.from(() ->
                    {
                        serverWriteSuccessLatch.countDown();

                        // Release the buffer.
                        buffer.release();

                        // Complete the Handler callback.
                        callback.succeeded();
                    }, x ->
                    {
                        if (serverEndPointWriteSuccessLatch.getCount() == 0L)
                            serverWriteFailureLatch.countDown();

                        // Release the buffer.
                        buffer.release();

                        // Complete the Handler callback.
                        callback.failed(x);
                    }));
                return true;
            }
        });
        server.start();

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            HttpTester.Request request = new HttpTester.Request();
            request.setMethod("GET");
            request.setHeader("Host", "localhost");
            request.setURI("/");
            ByteBuffer buffer = request.generate();

            client.write(buffer);

            assertTrue(serverEndPointWriteSuccessLatch.await(5, TimeUnit.SECONDS));
            assertFalse(serverWriteSuccessLatch.await(1, TimeUnit.SECONDS));

            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
            assertThat(response.getStatus(), is(200));
        }
    }
}
