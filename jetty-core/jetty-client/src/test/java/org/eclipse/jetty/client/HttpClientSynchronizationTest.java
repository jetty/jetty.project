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

import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that synchronization performed from outside HttpClient does not cause deadlocks
 */
public class HttpClientSynchronizationTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSynchronizationOnException(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());
        int port = connector.getLocalPort();
        server.stop();

        int count = 10;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            Request request = client.newRequest("localhost", port)
                .scheme(scenario.getScheme())
                .path("/" + i);

            Object lock = this;
            synchronized (lock)
            {
                request.send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Response response, Throwable failure)
                    {
                        synchronized (lock)
                        {
                            assertThat(failure, Matchers.instanceOf(ConnectException.class));
                            latch.countDown();
                        }
                    }
                });
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSynchronizationOnComplete(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        int count = 10;
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/" + i);

            Object lock = this;
            synchronized (lock)
            {
                request.send(new Response.Listener.Adapter()
                {
                    @Override
                    public void onComplete(Result result)
                    {
                        synchronized (lock)
                        {
                            assertFalse(result.isFailed());
                            latch.countDown();
                        }
                    }
                });
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
