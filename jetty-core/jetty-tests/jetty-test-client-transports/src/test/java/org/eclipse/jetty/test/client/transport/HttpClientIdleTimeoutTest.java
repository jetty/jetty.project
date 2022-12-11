//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientIdleTimeoutTest extends AbstractTest
{
    private final long idleTimeout = 1000;

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientIdleTimeout(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                // Do not succeed the callback if it's a timeout request.
                if (!Request.getPathInContext(request).equals("/timeout"))
                    callback.succeeded();
            }
        });
        client.setIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .path("/timeout")
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testRequestIdleTimeout(Transport transport) throws Exception
    {
        start(transport, new Handler.Processor()
        {
            @Override
            public void doProcess(Request request, Response response, Callback callback) throws Exception
            {
                // Do not succeed the callback if it's a timeout request.
                if (!Request.getPathInContext(request).equals("/timeout"))
                    callback.succeeded();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest(newURI(transport))
            .path("/timeout")
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
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
        ContentResponse response2 = client.newRequest(newURI(transport)).send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());
    }
}
