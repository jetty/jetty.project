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

package org.eclipse.jetty.test.client.transport;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.CompletableTask;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerRetainContentTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testRetainPOST(TransportType transportType) throws Exception
    {
        Queue<Content.Chunk> chunks = new ConcurrentLinkedQueue<>();
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                CompletableTask<Void> task = new CompletableTask<>()
                {
                    @Override
                    public void run()
                    {
                        while (true)
                        {
                            Content.Chunk chunk = request.read();
                            if (chunk == null)
                            {
                                request.demand(this);
                                return;
                            }
                            if (Content.Chunk.isFailure(chunk))
                            {
                                completeExceptionally(chunk.getFailure());
                                return;
                            }
                            chunks.add(chunk);
                            if (chunk.isLast())
                            {
                                complete(null);
                                return;
                            }
                        }
                    }
                };
                callback.completeWith(task.start());
                return true;
            }
        });
        ArrayByteBufferPool byteBufferPool = (ArrayByteBufferPool)server.getByteBufferPool();
        byteBufferPool.setStatisticsEnabled(true);
        long initialMemory = byteBufferPool.getDirectMemory() + byteBufferPool.getHeapMemory() + byteBufferPool.getReserved();

        AsyncRequestContent content = new AsyncRequestContent();

        Callback.Completable one = new Callback.Completable();
        content.write(false, BufferUtil.toReadableBuffer("1"), one);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transportType))
            .method("POST")
            .body(content)
            .send(result ->
            {
                assertThat(result.getResponse().getStatus(), is(HttpStatus.OK_200));
                latch.countDown();
            });

        Callback.Completable two = new Callback.Completable();
        content.write(false, BufferUtil.toReadableBuffer("2"), two);

        one.get(5, TimeUnit.SECONDS);
        two.get(5, TimeUnit.SECONDS);

        int count = 1000;
        for (int i = 3; i < count; i++)
        {
            Callback.Completable complete = new Callback.Completable();
            content.write(false, BufferUtil.toReadableBuffer(Integer.toString(i)), complete);
            content.flush();
            complete.get(5, TimeUnit.SECONDS);
        }

        Callback.Completable end = new Callback.Completable();
        content.write(true, BufferUtil.toReadableBuffer("x"), end);
        content.close();
        end.get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        long finalMemory = byteBufferPool.getDirectMemory() + byteBufferPool.getHeapMemory() + byteBufferPool.getReserved();

        // Release all chunks retained on the server.
        chunks.forEach(Content.Chunk::release);

        // Estimate derived from runs of this test.
        // The chunks are very small (at most 3 characters), and on the
        // server we should reuse the input buffer as much as possible.
        long estimatedExpected = 128 * 1024;
        // TODO rework H2/H2C connectors to be more frugal with their buffers
        if (!transportType.name().contains("H2"))
            assertThat(byteBufferPool.dump(), finalMemory - initialMemory, lessThanOrEqualTo(estimatedExpected));
    }
}
