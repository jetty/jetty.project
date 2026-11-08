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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.test.client.transport.AbstractTest.TransportType.H3_QUICHE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientIdleTimeoutTest extends AbstractTest
{
    private final long idleTimeout = 1000;

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientIdleTimeout(TransportType transportType) throws Exception
    {
        Assumptions.assumeTrue(transportType != H3_QUICHE, "Test is flaky on H3, see #14901");

        long serverIdleTimeout = idleTimeout * 2;
        AtomicReference<Callback> serverCallbackRef = new AtomicReference<>();
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not succeed the callback if it's a timeout request.
                if (Request.getPathInContext(request).equals("/timeout"))
                    request.addFailureListener(x -> serverCallbackRef.set(callback));
                else
                    callback.succeeded();
                return true;
            }
        });
        connector.setIdleTimeout(serverIdleTimeout);
        client.setIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transportType))
            .path("/timeout")
            .body(new StringRequestContent("some data"))
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI(transportType))
            .timeout(5, TimeUnit.SECONDS)
            .body(new StringRequestContent("more data"))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait for the server's idle timeout to trigger to give it a chance to clean up its resources.
        Callback callback = await().atMost(2 * serverIdleTimeout, TimeUnit.MILLISECONDS).until(serverCallbackRef::get, notNullValue());
        callback.failed(new TimeoutException());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestIdleTimeout(TransportType transportType) throws Exception
    {
        Assumptions.assumeTrue(transportType != H3_QUICHE, "Test is flaky on H3, see #14901");

        long serverIdleTimeout = idleTimeout * 2;
        AtomicReference<Callback> serverCallbackRef = new AtomicReference<>();
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Do not succeed the callback if it's a timeout request.
                if (Request.getPathInContext(request).equals("/timeout"))
                    request.addFailureListener(x -> serverCallbackRef.set(callback));
                else
                    callback.succeeded();
                return true;
            }
        });
        connector.setIdleTimeout(serverIdleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transportType))
            .path("/timeout")
            .body(new StringRequestContent("some data"))
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI(transportType))
            .body(new StringRequestContent("more data"))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait for the server's idle timeout to trigger to give it a chance to clean up its resources.
        Callback callback = await().atMost(2 * serverIdleTimeout, TimeUnit.MILLISECONDS).until(serverCallbackRef::get, notNullValue());
        callback.failed(new TimeoutException());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleClientIdleTimeout(TransportType transportType) throws Exception
    {
        start(transportType, new EmptyServerHandler());
        client.setIdleTimeout(idleTimeout);

        // Make a first request to open a connection.
        ContentResponse response = client.newRequest(newURI(transportType)).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Let the connection idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Verify that after the timeout we can make another request.
        response = client.newRequest(newURI(transportType)).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleServerIdleTimeout(TransportType transportType) throws Exception
    {
        start(transportType, new EmptyServerHandler());
        connector.setIdleTimeout(idleTimeout);

        ContentResponse response1 = client.newRequest(newURI(transportType)).send();
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        // Let the server idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Make sure we can make another request successfully.
        ContentResponse response2 = client.newRequest(newURI(transportType))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testStreamIdleTimeoutIsRescheduled(TransportType transportType) throws Exception
    {
        CountDownLatch handlerLatch = new CountDownLatch(1);
        AtomicInteger listenerCounter = new AtomicInteger();
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request.addIdleTimeoutListener(e ->
                {
                    int count = listenerCounter.getAndIncrement();
                    // Returning true marks the request as failed, but the handling goes on.
                    return count > 0;
                });

                // Assert that the first timeout is transient, which only happens if there is demand.
                Content.Chunk chunk = null;
                while (chunk == null)
                {
                    try (Blocker.Runnable runnable = Blocker.runnable())
                    {
                        request.demand(runnable);
                        runnable.block(2 * idleTimeout, TimeUnit.MILLISECONDS);
                    }
                    chunk = request.read();
                }
                assertNotNull(chunk);
                assertFalse(chunk.isLast());
                assertInstanceOf(TimeoutException.class, chunk.getFailure());

                // After the transient timeout, we can still read.
                assertNull(request.read());

                // Content must eventually be TimeoutException after the timeout listener fired twice since it returned true.
                chunk = await().atMost(3 * idleTimeout, TimeUnit.MILLISECONDS).until(request::read, notNullValue());
                assertTrue(chunk.isLast());
                assertInstanceOf(TimeoutException.class, chunk.getFailure());

                assertThat(listenerCounter.get(), greaterThanOrEqualTo(2));

                callback.succeeded();
                handlerLatch.countDown();
                return true;
            }
        });
        setStreamIdleTimeout(idleTimeout);

        AtomicReference<Result> resultRef = new AtomicReference<>();
        AsyncRequestContent content = new AsyncRequestContent();
        client.newRequest(newURI(transportType))
            .method(HttpMethod.POST)
            .body(content)
            .timeout(5, TimeUnit.SECONDS)
            .send(resultRef::set);

        assertTrue(handlerLatch.await(5, TimeUnit.SECONDS));

        // Handler is done, close the content so the client can finish the request.
        content.close();
        Result result = await().atMost(5, TimeUnit.SECONDS).until(resultRef::get, notNullValue());
        int status = switch (transportType)
        {
            case HTTP, HTTPS -> HttpStatus.OK_200;
            default -> HttpStatus.INTERNAL_SERVER_ERROR_500;
        };
        assertEquals(status, result.getResponse().getStatus());
    }
}
