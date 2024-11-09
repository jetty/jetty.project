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

import java.io.IOException;
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
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
    public void testRetainPOST(Transport transport) throws Exception
    {
        Queue<Content.Chunk> chunks = new ConcurrentLinkedQueue<>();
        CountDownLatch blocked = new CountDownLatch(1);

        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                while (true)
                {
                    Content.Chunk chunk = request.read();
                    if (chunk == null)
                    {
                        try (Blocker.Runnable blocker = Blocker.runnable())
                        {
                            blocked.countDown();
                            request.demand(blocker);
                            blocker.block();
                        }
                        catch (IOException e)
                        {
                            // ignored
                        }
                        continue;
                    }

                    chunks.add(chunk);
                    if (chunk.isLast())
                        break;
                }
                callback.succeeded();
                return true;
            }
        });
        AsyncRequestContent content = new AsyncRequestContent();

        Callback.Completable one = new Callback.Completable();
        content.write(false, BufferUtil.toBuffer("1"), one);

        ArrayByteBufferPool byteBufferPool = (ArrayByteBufferPool)server.getByteBufferPool();

        long baseMemory = byteBufferPool.getDirectMemory() + byteBufferPool.getHeapMemory() + byteBufferPool.getReserved();

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .method("POST")
            .body(content)
            .send(result ->
            {
                assertThat(result.getResponse().getStatus(), is(HttpStatus.OK_200));
                latch.countDown();
            });

        Callback.Completable two = new Callback.Completable();
        content.write(false, BufferUtil.toBuffer("2"), two);
        content.flush();

        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        one.get(5, TimeUnit.SECONDS);
        two.get(5, TimeUnit.SECONDS);

        final int CHUNKS = 1000;
        for (int i = 3; i < CHUNKS; i++)
        {
            Callback.Completable complete = new Callback.Completable();
            content.write(false, BufferUtil.toBuffer(Integer.toString(i)), complete);
            content.flush();
            complete.get(5, TimeUnit.SECONDS);
        }

        Callback.Completable end = new Callback.Completable();
        content.write(true, BufferUtil.toBuffer("x"), end);
        content.close();
        end.get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        long finalMemory = byteBufferPool.getDirectMemory() + byteBufferPool.getHeapMemory() + byteBufferPool.getReserved();

        long totalData = 0;
        for (Content.Chunk chunk : chunks)
        {
            chunk.release();
            if (chunk.hasRemaining())
                totalData += chunk.remaining();
        }

        assertThat(finalMemory - baseMemory, lessThanOrEqualTo((transport.isSecure() ? 100 : 32) * 1024L));

        client.close();
    }
}
