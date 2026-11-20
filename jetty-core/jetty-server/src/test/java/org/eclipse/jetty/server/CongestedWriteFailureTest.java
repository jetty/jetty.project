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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritePendingException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CongestedWriteFailureTest
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
    public void testGetInvocationTypeAfterCongestedWriteFailed() throws Exception
    {
        AtomicReference<Callback> writeCallbackRef = new AtomicReference<>();
        CountDownLatch serverWriteFailureLatch = new CountDownLatch(1);

        ServerConnector connector = new ServerConnector(server, 1, 1)
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
            {
                SocketChannelEndPoint endpoint = new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
                    {
                        writeCallbackRef.set(callback);
                        super.write(callback, buffers);
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
                // Large write, it blocks due to TCP congestion.
                RetainableByteBuffer buffer = server.getByteBufferPool().acquire(128 * 1024 * 1024, true);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                byteBuffer.clear();
                response.write(true, byteBuffer, Callback.from(callback::succeeded, x ->
                {
                    serverWriteFailureLatch.countDown();
                    buffer.release();
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
            client.write(request.generate());

            await().atMost(5, TimeUnit.SECONDS).until(() -> writeCallbackRef.get() != null);

            // Reset the connection to fail the congested write.
            client.socket().setSoLinger(true, 0);
            client.close();

            assertTrue(serverWriteFailureLatch.await(5, TimeUnit.SECONDS));
        }

        // A selector thread that read the WriteFlusher pending state
        // before the write failed may ask the invocation type of the
        // write callback after the write failed.
        assertThat(Invocable.getInvocationType(writeCallbackRef.get()), notNullValue());
    }
}
