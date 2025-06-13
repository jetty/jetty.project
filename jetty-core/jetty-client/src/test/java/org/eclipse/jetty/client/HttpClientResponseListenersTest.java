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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientResponseListenersTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseListenerForMultipleEventsIsInvokedOncePerEvent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        Response.Listener listener = new Response.Listener()
        {
            @Override
            public void onBegin(Response response)
            {
                counter.incrementAndGet();
            }

            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                // Number of header may vary, so don't count
                return true;
            }

            @Override
            public void onHeaders(Response response)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onContent(Response response, Content.Chunk chunk, Runnable demander)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onSuccess(Response response)
            {
                counter.incrementAndGet();
            }

            @Override
            public void onFailure(Response response, Throwable failure)
            {
                // Should not be invoked
                counter.incrementAndGet();
            }

            @Override
            public void onComplete(Result result)
            {
                assertEquals(200, result.getResponse().getStatus());
                counter.incrementAndGet();
                latch.countDown();
            }
        };
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(listener)
            .onResponseHeader(listener)
            .onResponseHeaders(listener)
            .onResponseContent(listener)
            .onResponseContentAsync(listener)
            .onResponseSuccess(listener)
            .onResponseFailure(listener)
            .onComplete(listener)
            .send(listener);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        int expectedEventsTriggeredByResponseListeners = 4;
        int expectedEventsTriggeredBySendListener = 4;
        int expected = expectedEventsTriggeredByResponseListeners + expectedEventsTriggeredBySendListener;
        assertEquals(expected, counter.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testResponseListenerAddedInResponseListenerEvent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        CountDownLatch beginLatch = new CountDownLatch(1);
        CountDownLatch headersLatch = new CountDownLatch(1);
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            // Listeners for future events will be notified.
            .onResponseBegin(r1 -> r1.getRequest().onResponseHeaders(r2 -> beginLatch.countDown()))
            // Listeners for current or past events won't be notified.
            .onResponseHeaders(r1 -> r1.getRequest().onResponseHeaders(r2 -> headersLatch.countDown()))
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(beginLatch.await(5, TimeUnit.SECONDS));
        assertFalse(headersLatch.await(1, TimeUnit.SECONDS));
    }
}
