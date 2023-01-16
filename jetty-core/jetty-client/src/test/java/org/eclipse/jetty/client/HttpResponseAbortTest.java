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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpResponseAbortTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnBegin(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(response -> response.abort(new Exception()))
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeader(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader((response, field) ->
            {
                response.abort(new Exception());
                return true;
            })
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeaders(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeaders(response -> response.abort(new Exception()))
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, Response response)
            {
                try
                {
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{1}));
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{2}));
                }
                catch (IOException ignored)
                {
                    // The client may have already closed, and we'll get an exception here, but it's expected
                }
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContent((response, content) -> response.abort(new Exception()))
            .send(result ->
            {
                assertTrue(result.isFailed());
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContentBeforeRequestTermination(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, Response response)
            {
                try
                {
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{1}));
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{2}));
                }
                catch (IOException ignored)
                {
                    // The client may have already closed, and we'll get an exception here, but it's expected
                }
            }
        });

        AsyncRequestContent requestContent = new AsyncRequestContent(ByteBuffer.allocate(1));
        AtomicInteger completes = new AtomicInteger();
        CountDownLatch completeLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .body(requestContent)
            .onResponseContent((response, content) ->
            {
                try
                {
                    response.abort(new Exception());
                    requestContent.close();
                    // Delay to let the request side to finish its processing.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            })
            .send(result ->
            {
                completes.incrementAndGet();
                assertTrue(result.isFailed());
                completeLatch.countDown();
            });
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

        // Wait to be sure that the complete event is only notified once.
        Thread.sleep(1000);

        assertEquals(1, completes.get());
    }
}
