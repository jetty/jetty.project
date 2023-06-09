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
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerTimeoutsTest extends AbstractTest
{
    private static final long IDLE_TIMEOUT = 1000;

    @Override
    public AbstractConnector newConnector(Transport transport, Server server)
    {
        AbstractConnector connector = super.newConnector(transport, server);

        switch (transport)
        {
            case H2, H2C -> connector.getConnectionFactory(HTTP2ServerConnectionFactory.class).setStreamIdleTimeout(IDLE_TIMEOUT);
            case H3 -> connector.getConnectionFactory(HTTP3ServerConnectionFactory.class).getHTTP3Configuration().setStreamIdleTimeout(IDLE_TIMEOUT);
            default -> connector.setIdleTimeout(IDLE_TIMEOUT);
        }
        return connector;
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutNoListener(Transport transport) throws Exception
    {
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler never completes the callback
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
    }
    
    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutNoListenerDemand(Transport transport) throws Exception
    {
        CountDownLatch demanded = new CountDownLatch(1);
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler never completes the callback
                requestRef.set(request);
                callbackRef.set(callback);
                request.demand(demanded::countDown);
                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent();
        org.eclipse.jetty.client.Request request = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .headers(f -> f.put(HttpHeader.CONTENT_LENGTH, 10))
            .onResponseSuccess(s ->
                content.close())
            .body(content);
        FutureResponseListener futureResponse = new FutureResponseListener(request);
        request.send(futureResponse);

        assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
        Content.Chunk chunk = requestRef.get().read();
        assertThat(chunk, instanceOf(Content.Chunk.Error.class));
        Throwable cause = ((Content.Chunk.Error)chunk).getCause();
        assertThat(cause, instanceOf(TimeoutException.class));
        callbackRef.get().failed(cause);

        ContentResponse response = futureResponse.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutFalseListener(Transport transport) throws Exception
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
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
        assertThat(error.get(), instanceOf(TimeoutException.class));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutTrueListener(Transport transport) throws Exception
    {
        CompletableFuture<Callback> callbackOnTimeout = new CompletableFuture<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t ->
                {
                    if (t instanceof TimeoutException)
                    {
                        callbackOnTimeout.complete(callback);
                        return true;
                    }
                    return false;
                });
                return true;
            }
        });

        org.eclipse.jetty.client.Request request = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS);
        FutureResponseListener futureResponse = new FutureResponseListener(request);
        request.send(futureResponse);

        callbackOnTimeout.get(3 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS).succeeded();

        ContentResponse response = futureResponse.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutTrueFalseListener(Transport transport) throws Exception
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addErrorListener(t ->
                {
                    if (t instanceof TimeoutException)
                        return error.getAndSet(t) == null;
                    t.printStackTrace();
                    return false;
                });
                return true;
            }
        });

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(response.getContentAsString(), containsStringIgnoringCase("HTTP ERROR 500 java.util.concurrent.TimeoutException: Idle timeout"));
        assertThat(error.get(), instanceOf(TimeoutException.class));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleTimeoutListenerTrueDemand(Transport transport) throws Exception
    {
        CountDownLatch demanded = new CountDownLatch(1);
        CountDownLatch recalled = new CountDownLatch(1);
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        AtomicReference<Request> requestRef = new AtomicReference<>();
        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Handler never completes the callback
                requestRef.set(request);
                callbackRef.set(callback);
                request.addErrorListener(t ->
                {
                    if (t instanceof TimeoutException)
                    {
                        if (error.isDone())
                            recalled.countDown();
                        else
                            error.complete(t);
                        return true;
                    }
                    t.printStackTrace();
                    return false;
                });
                request.demand(demanded::countDown);

                return true;
            }
        });

        AsyncRequestContent content = new AsyncRequestContent();
        org.eclipse.jetty.client.Request request = client.newRequest(newURI(transport))
            .timeout(IDLE_TIMEOUT * 5, TimeUnit.MILLISECONDS)
            .headers(f -> f.put(HttpHeader.CONTENT_LENGTH, 10))
            .onResponseSuccess(s ->
                content.close())
            .body(content);
        FutureResponseListener futureResponse = new FutureResponseListener(request);
        request.send(futureResponse);

        assertTrue(demanded.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
        Throwable t = error.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
        assertThat(t, instanceOf(TimeoutException.class));
        Content.Chunk chunk = requestRef.get().read();
        assertThat(chunk, instanceOf(Content.Chunk.Error.class));
        Throwable cause = ((Content.Chunk.Error)chunk).getCause();
        assertThat(cause, instanceOf(TimeoutException.class));

        // wait for another timeout
        try
        {
            System.err.println("waiting...");
            assertTrue(recalled.await(2 * IDLE_TIMEOUT, TimeUnit.MILLISECONDS));
        }
        finally
        {
            System.err.println("wait over");
        }

        callbackRef.get().succeeded();

        ContentResponse response = futureResponse.get(IDLE_TIMEOUT / 2, TimeUnit.MILLISECONDS);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }
}
