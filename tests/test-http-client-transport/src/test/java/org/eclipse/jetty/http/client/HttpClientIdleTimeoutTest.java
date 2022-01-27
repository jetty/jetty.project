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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientIdleTimeoutTest extends AbstractTest<TransportScenario>
{
    private final long idleTimeout = 1000;

    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testClientIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        scenario.startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                if (target.equals("/timeout"))
                {
                    AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(0);
                }
            }
        });
        scenario.startClient(httpClient -> httpClient.setIdleTimeout(idleTimeout));

        final CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/timeout")
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = scenario.client.newRequest(scenario.newURI()).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRequestIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            {
                baseRequest.setHandled(true);
                if (target.equals("/timeout"))
                {
                    AsyncContext asyncContext = request.startAsync();
                    asyncContext.setTimeout(0);
                }
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        scenario.client.newRequest(scenario.newURI())
            .path("/timeout")
            .idleTimeout(idleTimeout, TimeUnit.MILLISECONDS)
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Verify that after the timeout we can make another request.
        ContentResponse response = scenario.client.newRequest(scenario.newURI()).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleClientIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        scenario.startServer(new EmptyServerHandler());
        scenario.startClient(httpClient -> httpClient.setIdleTimeout(idleTimeout));

        // Make a first request to open a connection.
        ContentResponse response = scenario.client.newRequest(scenario.newURI()).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Let the connection idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Verify that after the timeout we can make another request.
        response = scenario.client.newRequest(scenario.newURI()).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testIdleServerIdleTimeout(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new EmptyServerHandler());
        scenario.setConnectionIdleTimeout(idleTimeout);

        ContentResponse response1 = scenario.client.newRequest(scenario.newURI()).send();
        assertEquals(HttpStatus.OK_200, response1.getStatus());

        // Let the server idle timeout.
        Thread.sleep(2 * idleTimeout);

        // Make sure we can make another request successfully.
        ContentResponse response2 = scenario.client.newRequest(scenario.newURI()).send();
        assertEquals(HttpStatus.OK_200, response2.getStatus());
    }
}
