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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientIdleTimeoutTest extends AbstractTest
{
    private final long idleTimeout = 1000;

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientIdleTimeout(Transport transport) throws Exception
    {
        long serverIdleTimeout = idleTimeout * 2;
        AtomicReference<Callback> serverCallbackRef = new AtomicReference<>();
        start(transport, new Handler.Abstract()
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
        client.newRequest(newURI(transport))
            .path("/timeout")
            .body(new StringRequestContent("some data"))
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI(transport))
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
    public void testRequestIdleTimeout(Transport transport) throws Exception
    {
        long serverIdleTimeout = idleTimeout * 2;
        AtomicReference<Callback> serverCallbackRef = new AtomicReference<>();
        start(transport, new Handler.Abstract()
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
        client.newRequest(newURI(transport))
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
        ContentResponse response = client.newRequest(newURI(transport))
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
    public void testIdleClientIdleTimeout(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());
        client.setIdleTimeout(idleTimeout);

        // Make a first request to open a connection.
        ContentResponse response = client.newRequest(newURI(transport)).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Let the connection idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Verify that after the timeout we can make another request.
        response = client.newRequest(newURI(transport)).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testIdleServerIdleTimeout(Transport transport) throws Exception
    {
        start(transport, new EmptyServerHandler());
        connector.setIdleTimeout(idleTimeout);

        ContentResponse response1 = client.newRequest(newURI(transport)).send();
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        // Let the server idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Make sure we can make another request successfully.
        ContentResponse response2 = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());
    }
}
