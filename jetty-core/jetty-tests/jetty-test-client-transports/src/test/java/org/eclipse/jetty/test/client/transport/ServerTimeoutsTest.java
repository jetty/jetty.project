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
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeoutsTest extends AbstractTest
{
    private static final long IDLE_TIMEOUT = 1000L;

    @Override
    protected void prepareServer(TransportType transportType, Handler handler) throws Exception
    {
        super.prepareServer(transportType, handler);
        setStreamIdleTimeout(IDLE_TIMEOUT);
    }

    public static Stream<Arguments> transportsAndIdleTimeoutListener()
    {
        Collection<TransportType> transportTypes = transports();
        return Stream.concat(
            transportTypes.stream().map(t -> Arguments.of(t, false)),
            transportTypes.stream().map(t -> Arguments.arguments(t, true)));
    }

    @ParameterizedTest
    @MethodSource("transportsAndIdleTimeoutListener")
    public void testIdleTimeout(TransportType transportType, boolean addIdleTimeoutListener) throws Exception
    {
        AtomicBoolean listenerCalled = new AtomicBoolean();
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (addIdleTimeoutListener)
                    request.addIdleTimeoutListener(t -> listenerCalled.compareAndSet(false, true));
                request.addFailureListener(callback::failed);
                // Do not complete the callback, so it idle times out.
                return true;
            }
        });

        // HTTP/2 receives the response headers, but may fail the response content.
        List<TransportType> transportTypesThatFail = List.of(TransportType.H2C, TransportType.H2);
        try
        {
            ContentResponse response = client.newRequest(newURI(transportType))
                .onResponseHeaders(r ->
                {
                    int status = r.getStatus();
                    if (status != HttpStatus.INTERNAL_SERVER_ERROR_500)
                        r.abort(new AssertionError("unexpected status " + status));
                })
                .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS)
                .send();

            // HTTP/2 might succeed.
            assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
        }
        catch (ExecutionException x)
        {
            assertThat(x.getCause(), instanceOf(IOException.class));
            // If it fails, it should be HTTP/2.
            assertThat(transportType, in(transportTypesThatFail));
        }

        if (addIdleTimeoutListener)
            assertTrue(listenerCalled.get());
    }

    @ParameterizedTest
    @MethodSource("transportsAndIdleTimeoutListener")
    @Tag("flaky")
    public void testIdleTimeoutWithDemand(TransportType transportType, boolean addIdleTimeoutListener) throws Exception
    {
        AtomicBoolean listenerCalled = new AtomicBoolean();
        CountDownLatch demanded = new CountDownLatch(1);
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (addIdleTimeoutListener)
                    request.addIdleTimeoutListener(t -> listenerCalled.compareAndSet(false, true));
                requestRef.set(request);
                callbackRef.set(callback);
                request.demand(demanded::countDown);
                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent();
        client.newRequest(newURI(transportType))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .headers(f -> f.put(HttpHeader.CONTENT_LENGTH, 10))
            .onResponseHeaders(r ->
            {
                assertThat(r.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
                content.close();
            })
            .body(content)
            .send(null);

        // Demand is invoked by the idle timeout.
        assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));

        // Reads should yield the idle timeout.
        Content.Chunk chunk = requestRef.get().read();
        assertTrue(Content.Chunk.isFailure(chunk, false));
        Throwable cause = chunk.getFailure();
        assertThat(cause, instanceOf(TimeoutException.class));

        // Can read again.
        assertNull(requestRef.get().read());

        // The idle timeout listener is not called as the timeout is delivered via demand callback.
        assertFalse(listenerCalled.get());

        // Complete the callback as the idle timeout listener promised.
        callbackRef.get().failed(cause);

        // The response must arrive to the client.
        await().atMost(5, TimeUnit.SECONDS).until(content::isClosed);
    }

    // TODO write side tests
}
