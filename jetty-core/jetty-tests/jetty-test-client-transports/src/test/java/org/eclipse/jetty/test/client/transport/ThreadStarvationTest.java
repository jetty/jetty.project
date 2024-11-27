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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadStarvationTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testReadStarvation(Transport transport) throws Exception
    {
        // Leave only 1 thread available to handle requests.
        // 1 acceptor (0 for H3), 1 selector, 1 available.
        int maxThreads = transport == Transport.H3 ? 2 : 3;
        AtomicReference<Thread> handlerThreadRef = new AtomicReference<>();
        prepareServer(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                handlerThreadRef.set(Thread.currentThread());
                // Perform a blocking read.
                String content = Content.Source.asString(request);
                Content.Sink.write(response, true, content, callback);
                return true;
            }
        });
        QueuedThreadPool serverThreads = (QueuedThreadPool)server.getThreadPool();
        serverThreads.setReservedThreads(0);
        serverThreads.setDetailedDump(true);
        serverThreads.setMinThreads(maxThreads);
        serverThreads.setMaxThreads(maxThreads);
        server.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, serverThreads.getReadyThreads()));

        startClient(transport);

        // Send one request that will block the last thread on the server.
        CountDownLatch responseLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent(UTF_8.encode("0"));
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
                responseLatch.countDown();
            });

        // Wait for the request to block on the server.
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread thread = handlerThreadRef.get();
            if (thread == null)
                return false;
            return thread.getState() == Thread.State.WAITING;
        });

        // Finish the request, the server should be able to process it.
        content.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutStarvation(Transport transport) throws Exception
    {
        long idleTimeout = 1000;
        AtomicReference<Thread> handlerThreadRef = new AtomicReference<>();
        CountDownLatch serverExceptionLatch = new CountDownLatch(1);
        prepareServer(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                handlerThreadRef.set(Thread.currentThread());
                try
                {
                    while (true)
                    {
                        Content.Chunk chunk = request.read();
                        if (chunk == null)
                        {
                            CountDownLatch latch = new CountDownLatch(1);
                            // The lambda passed to demand() has invocationType==BLOCKING.
                            request.demand(latch::countDown);
                            // Block here until more chunks are available.
                            assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
                            continue;
                        }
                        if (Content.Chunk.isFailure(chunk))
                            throw IO.rethrow(chunk.getFailure());
                        chunk.release();
                        if (chunk.isLast())
                            break;
                    }
                    callback.succeeded();
                    return true;
                }
                catch (IOException x)
                {
                    serverExceptionLatch.countDown();
                    throw x;
                }
            }
        });
        // Leave only 1 thread available to handle requests.
        // 1 acceptor (0 for H3), 1 selector, 1 available.
        int maxThreads = transport == Transport.H3 ? 2 : 3;
        QueuedThreadPool serverThreads = (QueuedThreadPool)server.getThreadPool();
        serverThreads.setReservedThreads(0);
        serverThreads.setDetailedDump(true);
        serverThreads.setMinThreads(maxThreads);
        serverThreads.setMaxThreads(maxThreads);
        setStreamIdleTimeout(idleTimeout);
        server.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(1, serverThreads.getReadyThreads()));

        startClient(transport);

        // Send one request that will block the last thread on the server.
        CountDownLatch responseLatch = new CountDownLatch(1);
        AsyncRequestContent content = new AsyncRequestContent();
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(2 * idleTimeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                // The response should arrive correctly,
                // it is the request that failed.
                assertTrue(result.isFailed());
                assertNull(result.getResponseFailure());
                assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, result.getResponse().getStatus());
                responseLatch.countDown();
            });

        // Wait for the request to block on the server.
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Thread thread = handlerThreadRef.get();
            if (thread == null)
                return false;
            return switch (thread.getState())
            {
                case WAITING, TIMED_WAITING -> true;
                default -> false;
            };
        });

        // The idle timeout should wake up the blocked read.
        assertTrue(serverExceptionLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(responseLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS), server.dump());
    }
}
