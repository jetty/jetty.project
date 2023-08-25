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

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FutureResponseListener;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeoutsTest extends AbstractTest
{
    private static final long IDLE_TIMEOUT = 1000L;

    @Override
    protected void prepareServer(Transport transport, Handler handler) throws Exception
    {
        super.prepareServer(transport, handler);
        setStreamIdleTimeout(IDLE_TIMEOUT);
    }

    public static Stream<Arguments> transportsAndTrueIdleTimeoutListeners()
    {
        Collection<Transport> transports = transports();
        return Stream.concat(
            transports.stream().map(t -> Arguments.of(t, false)),
            transports.stream().map(t -> Arguments.arguments(t, true)));
    }

    @ParameterizedTest
    @MethodSource("transportsAndTrueIdleTimeoutListeners")
    public void testIdleTimeout(Transport transport, boolean listener) throws Exception
    {
        AtomicBoolean listenerCalled = new AtomicBoolean();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {

                if (listener)
                    request.addIdleTimeoutListener(t -> listenerCalled.compareAndSet(false, true));

                // Do not complete the callback, so it idle times out.
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
        if (listener)
            assertTrue(listenerCalled.get());
    }

    @ParameterizedTest
    @MethodSource("transportsAndTrueIdleTimeoutListeners")
    @Tag("DisableLeakTracking")
    public void testIdleTimeoutWithDemand(Transport transport, boolean listener) throws Exception
    {
        AtomicBoolean listenerCalled = new AtomicBoolean();
        CountDownLatch demanded = new CountDownLatch(1);
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {

                if (listener)
                    request.addIdleTimeoutListener(t -> listenerCalled.compareAndSet(false, true));
                requestRef.set(request);
                callbackRef.set(callback);
                request.demand(demanded::countDown);
                return true;
            }
        });

        // The response will not be completed, so use a specialized listener.
        AsyncRequestContent content = new AsyncRequestContent();
        org.eclipse.jetty.client.Request request = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .headers(f -> f.put(HttpHeader.CONTENT_LENGTH, 10))
            .onResponseSuccess(s ->
                content.close())
            .body(content);
        FutureResponseListener futureResponse = new FutureResponseListener(request);
        request.send(futureResponse);

        // Demand is invoked by the idle timeout
        assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));

        // Reads should yield the idle timeout.
        Content.Chunk chunk = requestRef.get().read();
        // TODO change last to false in the next line if timeouts are transients
        assertTrue(Content.Chunk.isFailure(chunk, true));
        Throwable cause = chunk.getFailure();
        assertThat(cause, instanceOf(TimeoutException.class));

        /* TODO if transient timeout failures are supported then add this check
        // Can read again
        assertNull(requestRef.get().read());
        */

        // Complete the callback as the error listener promised.
        callbackRef.get().failed(cause);

        ContentResponse response = futureResponse.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));

        // listener is never called as timeout always delivered via demand
        assertFalse(listenerCalled.get());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutErrorListenerReturnsFalse(Transport transport) throws Exception
    {
        AtomicReference<Response> responseRef = new AtomicReference<>();
        CompletableFuture<Callback> callbackOnTimeout = new CompletableFuture<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                responseRef.set(response);
                request.addIdleTimeoutListener(t ->
                {
                    callbackOnTimeout.complete(callback);
                    return false; // ignore timeout
                });
                return true;
            }
        });

        org.eclipse.jetty.client.Request request = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS);
        FutureResponseListener futureResponse = new FutureResponseListener(request);
        request.send(futureResponse);

        // Get the callback as promised by the error listener.
        Callback callback = callbackOnTimeout.get(3 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(callback);
        Content.Sink.write(responseRef.get(), true, "OK", callback);

        ContentResponse response = futureResponse.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is("OK"));
    }

    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testIdleTimeoutErrorListenerReturnsFalseThenTrue(Transport transport) throws Exception
    {
        // TODO fix FCGI for multiple timeouts
        AtomicReference<Throwable> error = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addIdleTimeoutListener(t -> error.getAndSet(t) != null);
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .send();

        // The first time the listener returns true, but does not complete the callback,
        // so another idle timeout elapses.
        // The second time the listener returns false and the implementation produces the response.
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
        assertThat(error.get(), instanceOf(TimeoutException.class));
    }

    // TODO write side tests
}
