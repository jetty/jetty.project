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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
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

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutNoErrorListener(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Do not complete the callback, so it idle times out.
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsString("500 java.util.concurrent.TimeoutException"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutNoErrorListenerDemand(Transport transport) throws Exception
    {
        CountDownLatch demanded = new CountDownLatch(1);
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                requestRef.set(request);
                callbackRef.set(callback);
                request.demand(demanded::countDown);
                return true;
            }
        });

        // The response will not be completed, so use a specialized listener.
        CountDownLatch responseSuccess = new CountDownLatch(1);
        var listener = new BufferingResponseListener()
        {
            private int status;

            @Override
            public void onSuccess(org.eclipse.jetty.client.Response response)
            {
                status = response.getStatus();
                responseSuccess.countDown();
            }

            @Override
            public void onComplete(Result result)
            {
            }

            private int getStatus()
            {
                return status;
            }
        };
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .headers(h -> h.put(HttpHeader.CONTENT_LENGTH, 16))
            .body(new AsyncRequestContent())
            .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS)
            .send(listener);

        // Demand must be woken up by the idle timeout.
        assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));

        // Reads should yield the idle timeout.
        Content.Chunk chunk = requestRef.get().read();
        assertThat(chunk, instanceOf(Content.Chunk.Error.class));
        Throwable cause = ((Content.Chunk.Error)chunk).getCause();
        assertThat(cause, instanceOf(TimeoutException.class));

        // Complete the callback as the error listener promised.
        callbackRef.get().failed(cause);
        assertTrue(responseSuccess.await(5, TimeUnit.SECONDS));

        assertThat(listener.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(listener.getContentAsString(), containsString("500 java.util.concurrent.TimeoutException"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutErrorListenerReturnsFalse(Transport transport) throws Exception
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t ->
                {
                    error.set(t);
                    return false;
                });
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS)
            .send();

        // The error listener returned false, so the implementation produced the response.
        assertThat(error.get(), instanceOf(TimeoutException.class));
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsString("500 java.util.concurrent.TimeoutException"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutErrorListenerReturnsTrue(Transport transport) throws Exception
    {
        CompletableFuture<Callback> onTimeoutCompletable = new CompletableFuture<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t ->
                {
                    assertThat(t, instanceOf(TimeoutException.class));
                    onTimeoutCompletable.complete(callback);
                    return true;
                });
                return true;
            }
        });

        var request = client.newRequest(newURI(transport))
            .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS);
        FutureResponseListener listener = new FutureResponseListener(request);
        request.send(listener);

        // Complete the callback as promised by the error listener.
        onTimeoutCompletable.get(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS).succeeded();

        ContentResponse response = listener.get(5, TimeUnit.SECONDS);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutErrorListenerReturnsTrueThenFalse(Transport transport) throws Exception
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t -> error.getAndSet(t) == null);
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS)
            .send();

        // The first time the listener returns true, but does not complete the callback,
        // so another idle timeout elapses.
        // The second time the listener returns false and the implementation produces the response.
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsString("500 java.util.concurrent.TimeoutException"));
        assertThat(error.get(), instanceOf(TimeoutException.class));
    }
/*
    @Test
    public void testIdleTimeoutErrorListenerReturnsTrueDemand() throws Exception
    {
        CountDownLatch demanded = new CountDownLatch(1);
        CountDownLatch recalled = new CountDownLatch(1);
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                requestRef.set(request);
                callbackRef.set(callback);
                request.addErrorListener(t ->
                {
                    if (error.isDone())
                        recalled.countDown();
                    else
                        error.complete(t);
                    return true;
                });
                request.demand(demanded::countDown);

                return true;
            }
        });

        String request = """
            GET /path HTTP/1.0\r
            Host: hostname\r
            Content-Length: 10\r
            \r
            """;
        try (LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request))
        {
            assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
            Throwable t = error.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
            assertThat(t, instanceOf(TimeoutException.class));

            Content.Chunk chunk = requestRef.get().read();
            assertThat(chunk, instanceOf(Content.Chunk.Error.class));
            Throwable cause = ((Content.Chunk.Error)chunk).getCause();
            assertThat(cause, instanceOf(TimeoutException.class));

            // Wait for another idle timeout.
            assertTrue(recalled.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));

            // Complete the callback as promised by the error listener.
            callbackRef.get().succeeded();

            String rawResponse = endPoint.getResponse();
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    @Test
    public void testIdleTimeoutNoErrorListenerWriteCallbackFails() throws Exception
    {
        Callback.Completable completable = new Callback.Completable();
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.completeWith(completable);
                // Issue a large write to be TCP congested and idle timeout.
                response.write(true, ByteBuffer.allocate(128 * 1024 *1024), completable);
                return true;
            }
        });

        String request = """
            GET /path HTTP/1.0\r
            Host: localhost\r
            \r
            """;
        try (LocalConnector.LocalEndPoint endPoint = _connector.executeRequest(request))
        {
            // Do not read the response to cause TCP congestion.

            ExecutionException x = assertThrows(ExecutionException.class, () -> completable.get(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
            assertThat(x.getCause(), instanceOf(TimeoutException.class));
        }
    }
*/
}
