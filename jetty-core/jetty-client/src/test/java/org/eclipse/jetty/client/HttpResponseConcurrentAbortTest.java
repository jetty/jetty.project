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

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpResponseConcurrentAbortTest extends AbstractHttpClientServerTest
{
    private final CountDownLatch callbackLatch = new CountDownLatch(1);
    private final CountDownLatch failureLatch = new CountDownLatch(1);
    private final CountDownLatch completeLatch = new CountDownLatch(1);
    private final AtomicBoolean failureWasSync = new AtomicBoolean();
    private final AtomicBoolean completeWasSync = new AtomicBoolean();
    private final AtomicReference<Object> abortResult = new AtomicReference<>();

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnBegin(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(this::abort)
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasSync.get());
        assertTrue(completeWasSync.get());
        await().atMost(5, TimeUnit.SECONDS).until(abortResult::get, is(Boolean.TRUE));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeader(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader((response, field) ->
            {
                abort(response);
                return true;
            })
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasSync.get());
        assertTrue(completeWasSync.get());
        await().atMost(5, TimeUnit.SECONDS).until(abortResult::get, is(Boolean.TRUE));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeaders(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeaders(this::abort)
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasSync.get());
        assertTrue(completeWasSync.get());
        await().atMost(5, TimeUnit.SECONDS).until(abortResult::get, is(Boolean.TRUE));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Throwable
            {
                Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{1}));
            }
        });

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContent((response, content) -> abort(response))
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasSync.get());
        assertTrue(completeWasSync.get());
        await().atMost(5, TimeUnit.SECONDS).until(abortResult::get, is(Boolean.TRUE));
    }

    private void abort(final Response response)
    {
        new Thread("abort")
        {
            @Override
            public void run()
            {
                response.abort(new Exception()).whenComplete((aborted, x) ->
                {
                    if (x != null)
                        abortResult.set(x);
                    else
                        abortResult.set(aborted);
                });
            }
        }.start();

        try
        {
            // The failure callback must be executed by this thread,
            // after we return from this response callback.
            failureWasSync.set(!failureLatch.await(1, TimeUnit.SECONDS));

            // The complete callback must be executed by this thread,
            // after we return from this response callback.
            completeWasSync.set(!completeLatch.await(1, TimeUnit.SECONDS));

            callbackLatch.countDown();
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private class TestResponseListener extends Response.Listener.Adapter
    {
        @Override
        public void onFailure(Response response, Throwable failure)
        {
            failureLatch.countDown();
        }

        @Override
        public void onComplete(Result result)
        {
            assertTrue(result.isFailed());
            completeLatch.countDown();
        }
    }
}
